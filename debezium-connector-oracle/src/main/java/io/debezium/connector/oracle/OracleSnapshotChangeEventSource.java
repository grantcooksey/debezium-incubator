/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.source.spi.StreamingChangeEventSource;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.txmetadata.TransactionContext;
import io.debezium.relational.RelationalSnapshotChangeEventSource;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.schema.SchemaChangeEvent;
import io.debezium.schema.SchemaChangeEvent.SchemaChangeEventType;
import io.debezium.util.Clock;

/**
 * A {@link StreamingChangeEventSource} for Oracle.
 *
 * @author Gunnar Morling
 */
public class OracleSnapshotChangeEventSource extends RelationalSnapshotChangeEventSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(OracleSnapshotChangeEventSource.class);

    private final OracleConnectorConfig connectorConfig;
    private final OracleConnection jdbcConnection;

    public OracleSnapshotChangeEventSource(OracleConnectorConfig connectorConfig, OracleOffsetContext previousOffset, OracleConnection jdbcConnection,
                                           OracleDatabaseSchema schema, EventDispatcher<TableId> dispatcher, Clock clock,
                                           SnapshotProgressListener snapshotProgressListener) {
        super(connectorConfig, previousOffset, jdbcConnection, schema, dispatcher, clock, snapshotProgressListener);

        this.connectorConfig = connectorConfig;
        this.jdbcConnection = jdbcConnection;
    }

    @Override
    protected SnapshottingTask getSnapshottingTask(OffsetContext previousOffset) {
        boolean snapshotSchema = true;
        boolean snapshotData = true;

        // found a previous offset and the earlier snapshot has completed
        if (previousOffset != null && !previousOffset.isSnapshotRunning()) {
            snapshotSchema = false;
            snapshotData = false;
        }
        else {
            snapshotData = connectorConfig.getSnapshotMode().includeData();
        }

        return new SnapshottingTask(snapshotSchema, snapshotData);
    }

    @Override
    protected SnapshotContext prepare(ChangeEventSourceContext context) throws Exception {
        if (connectorConfig.getPdbName() != null) {
            jdbcConnection.setSessionToPdb(connectorConfig.getPdbName());
        }

        return new OracleSnapshotContext(
                connectorConfig.getPdbName() != null ? connectorConfig.getPdbName() : connectorConfig.getDatabaseName());
    }

    @Override
    protected Set<TableId> getAllTableIds(RelationalSnapshotContext ctx) throws Exception {
        return jdbcConnection.readTableNames(ctx.catalogName, null, null, new String[]{ "TABLE" });
    }

    @Override
    protected void lockTablesForSchemaSnapshot(ChangeEventSourceContext sourceContext, RelationalSnapshotContext snapshotContext)
            throws SQLException, InterruptedException {
        ((OracleSnapshotContext) snapshotContext).preSchemaSnapshotSavepoint = jdbcConnection.connection().setSavepoint("dbz_schema_snapshot");

        try (Statement statement = jdbcConnection.connection().createStatement()) {
            for (TableId tableId : snapshotContext.capturedTables) {
                if (!sourceContext.isRunning()) {
                    throw new InterruptedException("Interrupted while locking table " + tableId);
                }

                LOGGER.debug("Locking table {}", tableId);

                statement.execute("LOCK TABLE " + tableId.schema() + "." + tableId.table() + " IN EXCLUSIVE MODE");
            }
        }
    }

    @Override
    protected void releaseSchemaSnapshotLocks(RelationalSnapshotContext snapshotContext) throws SQLException {
        jdbcConnection.connection().rollback(((OracleSnapshotContext) snapshotContext).preSchemaSnapshotSavepoint);
    }

    @Override
    protected void determineSnapshotOffset(RelationalSnapshotContext ctx) throws Exception {
        Optional<Long> latestTableDdlScn = getLatestTableDdlScn(ctx);
        long currentScn;

        // we must use an SCN for taking the snapshot that represents a later timestamp than the latest DDL change than
        // any of the captured tables; this will not be a problem in practice, but during testing it may happen that the
        // SCN of "now" represents the same timestamp as a newly created table that should be captured; in that case
        // we'd get a ORA-01466 when running the flashback query for doing the snapshot
        do {
            currentScn = getCurrentScn(ctx);
        } while (areSameTimestamp(latestTableDdlScn.orElse(null), currentScn));

        ctx.offset = OracleOffsetContext.create()
                .logicalName(connectorConfig)
                .scn(currentScn)
                .transactionContext(new TransactionContext())
                .build();
    }

    private long getCurrentScn(SnapshotContext ctx) throws SQLException {
        try (Statement statement = jdbcConnection.connection().createStatement();
                ResultSet rs = statement.executeQuery("select CURRENT_SCN from V$DATABASE")) {

            if (!rs.next()) {
                throw new IllegalStateException("Couldn't get SCN");
            }

            return rs.getLong(1);
        }
    }

    /**
     * Whether the two SCNs represent the same timestamp or not (resolution is only 3 seconds).
     */
    private boolean areSameTimestamp(Long scn1, long scn2) throws SQLException {
        if (scn1 == null) {
            return false;
        }

        try (Statement statement = jdbcConnection.connection().createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1 FROM DUAL WHERE SCN_TO_TIMESTAMP(" + scn1 + ") = SCN_TO_TIMESTAMP(" + scn2 + ")")) {

            return rs.next();
        }
    }

    /**
     * Returns the SCN of the latest DDL change to the captured tables. The result will be empty if there's no table to
     * capture as per the configuration.
     */
    private Optional<Long> getLatestTableDdlScn(RelationalSnapshotContext ctx) throws SQLException {
        if (ctx.capturedTables.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder lastDdlScnQuery = new StringBuilder("SELECT TIMESTAMP_TO_SCN(MAX(last_ddl_time))")
                .append(" FROM all_objects")
                .append(" WHERE");

        for (TableId table : ctx.capturedTables) {
            lastDdlScnQuery.append(" (owner = '" + table.schema() + "' AND object_name = '" + table.table() + "') OR");
        }

        String query = lastDdlScnQuery.substring(0, lastDdlScnQuery.length() - 3).toString();
        try (Statement statement = jdbcConnection.connection().createStatement();
                ResultSet rs = statement.executeQuery(query)) {

            if (!rs.next()) {
                throw new IllegalStateException("Couldn't get latest table DDL SCN");
            }

            return Optional.of(rs.getLong(1));
        }
    }

    @Override
    protected void readTableStructure(ChangeEventSourceContext sourceContext, RelationalSnapshotContext snapshotContext) throws SQLException, InterruptedException {
        Set<String> schemas = snapshotContext.capturedTables.stream()
                .map(TableId::schema)
                .collect(Collectors.toSet());

        // reading info only for the schemas we're interested in as per the set of captured tables;
        // while the passed table name filter alone would skip all non-included tables, reading the schema
        // would take much longer that way
        for (String schema : schemas) {
            if (!sourceContext.isRunning()) {
                throw new InterruptedException("Interrupted while reading structure of schema " + schema);
            }

            jdbcConnection.readSchema(
                    snapshotContext.tables,
                    snapshotContext.catalogName,
                    schema,
                    connectorConfig.getTableFilters().dataCollectionFilter(),
                    null,
                    false);
        }
    }

    @Override
    protected SchemaChangeEvent getCreateTableEvent(RelationalSnapshotContext snapshotContext, Table table) throws SQLException {
        try (Statement statement = jdbcConnection.connection().createStatement();
                ResultSet rs = statement.executeQuery("select dbms_metadata.get_ddl( 'TABLE', '" + table.id().table() + "', '" + table.id().schema() + "' ) from dual")) {

            if (!rs.next()) {
                throw new IllegalStateException("Couldn't get metadata");
            }

            Object res = rs.getObject(1);
            String ddl = ((Clob) res).getSubString(1, (int) ((Clob) res).length());

            return new SchemaChangeEvent(snapshotContext.offset.getPartition(), snapshotContext.offset.getOffset(), snapshotContext.catalogName,
                    table.id().schema(), ddl, table, SchemaChangeEventType.CREATE, true);
        }
    }

    @Override
    protected Optional<String> getSnapshotSelect(SnapshotContext snapshotContext, TableId tableId) {
        long snapshotOffset = (Long) snapshotContext.offset.getOffset().get("scn");
        return Optional.of("SELECT * FROM " + tableId.schema() + "." + tableId.table() + " AS OF SCN " + snapshotOffset);
    }

    @Override
    protected void complete(SnapshotContext snapshotContext) {
        if (connectorConfig.getPdbName() != null) {
            jdbcConnection.resetSessionToCdb();
        }
    }

    /**
     * Mutable context which is populated in the course of snapshotting.
     */
    private static class OracleSnapshotContext extends RelationalSnapshotContext {

        private Savepoint preSchemaSnapshotSavepoint;

        public OracleSnapshotContext(String catalogName) throws SQLException {
            super(catalogName);
        }
    }
}
