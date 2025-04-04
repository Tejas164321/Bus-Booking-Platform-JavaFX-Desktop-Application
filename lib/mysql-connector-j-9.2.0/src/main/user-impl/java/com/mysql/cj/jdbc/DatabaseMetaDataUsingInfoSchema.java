/*
 * Copyright (c) 2005, 2024, Oracle and/or its affiliates.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License, version 2.0, as published by
 * the Free Software Foundation.
 *
 * This program is designed to work with certain software that is licensed under separate terms, as designated in a particular file or component or in
 * included license documentation. The authors of MySQL hereby grant you an additional permission to link the program and your derivative works with the
 * separately licensed software that they have either included with the program or referenced in the documentation.
 *
 * Without limiting anything contained in the foregoing, this file, which is part of MySQL Connector/J, is also subject to the Universal FOSS Exception,
 * version 1.0, a copy of which can be found at http://oss.oracle.com/licenses/universal-foss-exception.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License, version 2.0, for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA
 */

package com.mysql.cj.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.mysql.cj.Messages;
import com.mysql.cj.MysqlType;
import com.mysql.cj.ServerVersion;
import com.mysql.cj.conf.PropertyDefinitions.DatabaseTerm;
import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.exceptions.MysqlErrorNumbers;
import com.mysql.cj.jdbc.exceptions.SQLError;
import com.mysql.cj.jdbc.result.ResultSetFactory;
import com.mysql.cj.util.LRUCache;
import com.mysql.cj.util.StringUtils;

/**
 * DatabaseMetaData implementation that uses INFORMATION_SCHEMA
 */
public class DatabaseMetaDataUsingInfoSchema extends DatabaseMetaData {

    protected enum FunctionConstant {
        // COLUMN_TYPE values
        FUNCTION_COLUMN_UNKNOWN, FUNCTION_COLUMN_IN, FUNCTION_COLUMN_INOUT, FUNCTION_COLUMN_OUT, FUNCTION_COLUMN_RETURN, FUNCTION_COLUMN_RESULT,
        // NULLABLE values
        FUNCTION_NO_NULLS, FUNCTION_NULLABLE, FUNCTION_NULLABLE_UNKNOWN;
    }

    private static Map<ServerVersion, String> keywordsCache = Collections.synchronizedMap(new LRUCache<>(10));
    private static final Lock KEYWORDS_CACHE_LOCK = new ReentrantLock();

    protected DatabaseMetaDataUsingInfoSchema(JdbcConnection connToSet, String databaseToSet, ResultSetFactory resultSetFactory) throws SQLException {
        super(connToSet, databaseToSet, resultSetFactory);
    }

    protected ResultSet executeMetadataQuery(PreparedStatement pStmt) throws SQLException {
        ResultSet rs = pStmt.executeQuery();
        ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).setOwningStatement(null);
        return rs;
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException {
        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String dbPattern = getDatabase(catalog, schema);
        final String dbFilter = this.pedantic ? dbPattern : StringUtils.unQuoteIdentifier(dbPattern, this.quotedId);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);
        final String columnNameFilter = this.pedantic ? columnNamePattern : StringUtils.unQuoteIdentifier(columnNamePattern, this.quotedId);

        StringBuilder query = new StringBuilder(dbMapsToSchema ? "SELECT TABLE_CATALOG, TABLE_SCHEMA," : "SELECT TABLE_SCHEMA, NULL,");
        query.append(
                " TABLE_NAME, COLUMN_NAME, NULL AS GRANTOR, GRANTEE, PRIVILEGE_TYPE AS PRIVILEGE, IS_GRANTABLE FROM INFORMATION_SCHEMA.COLUMN_PRIVILEGES WHERE");
        if (dbFilter != null) {
            query.append(" TABLE_SCHEMA=? AND");
        }
        query.append(" TABLE_NAME = ?");
        if (columnNamePattern != null) {
            query.append(" AND COLUMN_NAME LIKE ?");
        }
        query.append(" ORDER BY COLUMN_NAME, PRIVILEGE_TYPE");

        PreparedStatement pStmt = null;

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            pStmt.setString(nextId++, dbFilter);
            pStmt.setString(nextId++, tableFilter);
            if (columnNameFilter != null) {
                pStmt.setString(nextId, columnNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(getColumnPrivilegesFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        String dbFilter = getDatabase(catalog, schemaPattern);
        dbFilter = this.pedantic ? dbFilter : StringUtils.unQuoteIdentifier(dbFilter, this.quotedId);
        final String tableNameFilter = this.pedantic ? tableNamePattern : StringUtils.unQuoteIdentifier(tableNamePattern, this.quotedId);
        final String columnNameFilter = this.pedantic ? columnNamePattern : StringUtils.unQuoteIdentifier(columnNamePattern, this.quotedId);

        StringBuilder query = new StringBuilder(dbMapsToSchema ? "SELECT TABLE_CATALOG, TABLE_SCHEMA," : "SELECT TABLE_SCHEMA, NULL,");
        query.append(" TABLE_NAME, COLUMN_NAME,");

        appendJdbcTypeMappingQuery(query, "DATA_TYPE", "COLUMN_TYPE");
        query.append(" AS DATA_TYPE, ");

        query.append("UPPER(CASE");
        if (this.tinyInt1isBit) {
            query.append(" WHEN UPPER(DATA_TYPE)='TINYINT' THEN CASE");
            query.append(
                    " WHEN LOCATE('ZEROFILL', UPPER(COLUMN_TYPE)) = 0 AND LOCATE('UNSIGNED', UPPER(COLUMN_TYPE)) = 0 AND LOCATE('(1)', COLUMN_TYPE) != 0 THEN ");
            query.append(this.transformedBitIsBoolean ? "'BOOLEAN'" : "'BIT'");
            query.append(" WHEN LOCATE('UNSIGNED', UPPER(COLUMN_TYPE)) != 0 AND LOCATE('UNSIGNED', UPPER(DATA_TYPE)) = 0 THEN 'TINYINT UNSIGNED'");
            query.append(" ELSE DATA_TYPE END ");
        }
        query.append(
                " WHEN LOCATE('UNSIGNED', UPPER(COLUMN_TYPE)) != 0 AND LOCATE('UNSIGNED', UPPER(DATA_TYPE)) = 0 AND LOCATE('SET', UPPER(DATA_TYPE)) <> 1 AND LOCATE('ENUM', UPPER(DATA_TYPE)) <> 1 THEN CONCAT(DATA_TYPE, ' UNSIGNED')");

        // spatial data types
        query.append(" WHEN UPPER(DATA_TYPE)='POINT' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='LINESTRING' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='POLYGON' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTIPOINT' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTILINESTRING' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTIPOLYGON' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='GEOMETRYCOLLECTION' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='GEOMCOLLECTION' THEN 'GEOMETRY'");

        query.append(" ELSE UPPER(DATA_TYPE) END) AS TYPE_NAME,");

        query.append("UPPER(CASE");
        query.append(" WHEN UPPER(DATA_TYPE)='DATE' THEN 10"); // supported range is '1000-01-01' to '9999-12-31'
        if (this.conn.getServerVersion().meetsMinimum(ServerVersion.parseVersion("5.6.4"))) {
            query.append(" WHEN UPPER(DATA_TYPE)='TIME'"); // supported range is '-838:59:59.000000' to '838:59:59.000000'
            query.append("  THEN 8+(CASE WHEN DATETIME_PRECISION>0 THEN DATETIME_PRECISION+1 ELSE DATETIME_PRECISION END)");
            query.append(" WHEN UPPER(DATA_TYPE)='DATETIME' OR"); // supported range is '1000-01-01 00:00:00.000000' to '9999-12-31 23:59:59.999999'
            query.append("  UPPER(DATA_TYPE)='TIMESTAMP'"); // supported range is '1970-01-01 00:00:01.000000' UTC to '2038-01-19 03:14:07.999999' UTC
            query.append("  THEN 19+(CASE WHEN DATETIME_PRECISION>0 THEN DATETIME_PRECISION+1 ELSE DATETIME_PRECISION END)");
        } else {
            query.append(" WHEN UPPER(DATA_TYPE)='TIME' THEN 8"); // supported range is '-838:59:59.000000' to '838:59:59.000000'
            query.append(" WHEN UPPER(DATA_TYPE)='DATETIME' OR"); // supported range is '1000-01-01 00:00:00.000000' to '9999-12-31 23:59:59.999999'
            query.append("  UPPER(DATA_TYPE)='TIMESTAMP'"); // supported range is '1970-01-01 00:00:01.000000' UTC to '2038-01-19 03:14:07.999999' UTC
            query.append("  THEN 19");
        }

        query.append(" WHEN UPPER(DATA_TYPE)='YEAR' THEN 4");
        if (this.tinyInt1isBit && !this.transformedBitIsBoolean) {
            query.append(
                    " WHEN UPPER(DATA_TYPE)='TINYINT' AND LOCATE('ZEROFILL', UPPER(COLUMN_TYPE)) = 0 AND LOCATE('UNSIGNED', UPPER(COLUMN_TYPE)) = 0 AND LOCATE('(1)', COLUMN_TYPE) != 0 THEN 1");
        }
        // workaround for Bug#69042 (16712664), "MEDIUMINT PRECISION/TYPE INCORRECT IN INFORMATION_SCHEMA.COLUMNS", I_S bug returns NUMERIC_PRECISION=7 for MEDIUMINT UNSIGNED when it must be 8.
        query.append(" WHEN UPPER(DATA_TYPE)='MEDIUMINT' AND LOCATE('UNSIGNED', UPPER(COLUMN_TYPE)) != 0 THEN 8");
        query.append(" WHEN UPPER(DATA_TYPE)='JSON' THEN 1073741824"); // JSON columns is limited to the value of the max_allowed_packet system variable (max value 1073741824)

        // spatial data types
        query.append(" WHEN UPPER(DATA_TYPE)='GEOMETRY' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE)='POINT' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE)='LINESTRING' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE)='POLYGON' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTIPOINT' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTILINESTRING' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTIPOLYGON' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE)='GEOMETRYCOLLECTION' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE)='GEOMCOLLECTION' THEN 65535");

        query.append(" WHEN CHARACTER_MAXIMUM_LENGTH IS NULL THEN NUMERIC_PRECISION");
        query.append(" WHEN CHARACTER_MAXIMUM_LENGTH > ");
        query.append(Integer.MAX_VALUE);
        query.append(" THEN ");
        query.append(Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_MAXIMUM_LENGTH");
        query.append(" END) AS COLUMN_SIZE,");

        query.append(maxBufferSize);
        query.append(" AS BUFFER_LENGTH,");

        query.append("UPPER(CASE");
        query.append(" WHEN UPPER(DATA_TYPE)='DECIMAL' THEN NUMERIC_SCALE");
        query.append(" WHEN UPPER(DATA_TYPE)='FLOAT' OR UPPER(DATA_TYPE)='DOUBLE' THEN");
        query.append(" CASE WHEN NUMERIC_SCALE IS NULL THEN 0");
        query.append(" ELSE NUMERIC_SCALE END");
        query.append(" ELSE NULL END) AS DECIMAL_DIGITS,");

        query.append("10 AS NUM_PREC_RADIX,");

        query.append("CASE");
        query.append(" WHEN IS_NULLABLE='NO' THEN ");
        query.append(columnNoNulls);
        query.append(" ELSE CASE WHEN IS_NULLABLE='YES' THEN ");
        query.append(columnNullable);
        query.append(" ELSE ");
        query.append(columnNullableUnknown);
        query.append(" END END AS NULLABLE,");

        query.append("COLUMN_COMMENT AS REMARKS,");
        query.append("COLUMN_DEFAULT AS COLUMN_DEF,");
        query.append("0 AS SQL_DATA_TYPE,");
        query.append("0 AS SQL_DATETIME_SUB,");

        query.append("CASE WHEN CHARACTER_OCTET_LENGTH > ");
        query.append(Integer.MAX_VALUE);
        query.append(" THEN ");
        query.append(Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_OCTET_LENGTH END AS CHAR_OCTET_LENGTH,");

        query.append("ORDINAL_POSITION, IS_NULLABLE, NULL AS SCOPE_CATALOG, NULL AS SCOPE_SCHEMA, NULL AS SCOPE_TABLE, NULL AS SOURCE_DATA_TYPE,");
        query.append("IF (EXTRA LIKE '%auto_increment%','YES','NO') AS IS_AUTOINCREMENT, ");
        query.append("IF (EXTRA LIKE '%GENERATED%','YES','NO') AS IS_GENERATEDCOLUMN ");

        query.append("FROM INFORMATION_SCHEMA.COLUMNS");

        StringBuilder condition = new StringBuilder();

        if (dbFilter != null) {
            condition.append(dbMapsToSchema && StringUtils.hasWildcards(dbFilter) ? " TABLE_SCHEMA LIKE ?" : " TABLE_SCHEMA = ?");
        }
        if (tableNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(StringUtils.hasWildcards(tableNameFilter) ? " TABLE_NAME LIKE ?" : " TABLE_NAME = ?");
        }
        if (columnNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(StringUtils.hasWildcards(columnNameFilter) ? " COLUMN_NAME LIKE ?" : " COLUMN_NAME = ?");
        }

        if (condition.length() > 0) {
            query.append(" WHERE");
        }
        query.append(condition);
        query.append(" ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION");

        PreparedStatement pStmt = null;
        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());

            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, dbFilter);
            }
            if (tableNameFilter != null) {
                pStmt.setString(nextId++, tableNameFilter);
            }
            if (columnNameFilter != null) {
                pStmt.setString(nextId, columnNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createColumnsFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    @Override
    public ResultSet getCrossReference(final String parentCatalog, final String parentSchema, final String parentTable, final String foreignCatalog,
            final String foreignSchema, final String foreignTable) throws SQLException {
        if (parentTable == null || foreignTable == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String parentDbFromTerm = getDatabase(parentCatalog, parentSchema);
        final String parentDbFilter = this.pedantic ? parentDbFromTerm : StringUtils.unQuoteIdentifier(parentDbFromTerm, this.quotedId);
        final String parentTableFilter = this.pedantic ? parentTable : StringUtils.unQuoteIdentifier(parentTable, this.quotedId);
        final String foreignDbFilter = getDatabase(foreignCatalog, foreignSchema);
        final String foreignTableFilter = this.pedantic ? foreignTable : StringUtils.unQuoteIdentifier(foreignTable, this.quotedId);

        StringBuilder query = new StringBuilder(
                dbMapsToSchema ? "SELECT DISTINCT A.CONSTRAINT_CATALOG AS PKTABLE_CAT, A.REFERENCED_TABLE_SCHEMA AS PKTABLE_SCHEM,"
                        : "SELECT DISTINCT A.REFERENCED_TABLE_SCHEMA AS PKTABLE_CAT,NULL AS PKTABLE_SCHEM,");
        query.append(" A.REFERENCED_TABLE_NAME AS PKTABLE_NAME, A.REFERENCED_COLUMN_NAME AS PKCOLUMN_NAME,");
        query.append(dbMapsToSchema ? " A.TABLE_CATALOG AS FKTABLE_CAT, A.TABLE_SCHEMA AS FKTABLE_SCHEM,"
                : " A.TABLE_SCHEMA AS FKTABLE_CAT, NULL AS FKTABLE_SCHEM,");
        query.append(" A.TABLE_NAME AS FKTABLE_NAME, A.COLUMN_NAME AS FKCOLUMN_NAME, A.ORDINAL_POSITION AS KEY_SEQ,");
        query.append(generateUpdateRuleClause());
        query.append(" AS UPDATE_RULE,");
        query.append(generateDeleteRuleClause());
        query.append(" AS DELETE_RULE, A.CONSTRAINT_NAME AS FK_NAME, TC.CONSTRAINT_NAME AS PK_NAME,");
        query.append(importedKeyNotDeferrable);
        query.append(" AS DEFERRABILITY FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE A");
        query.append(" JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS B USING (TABLE_SCHEMA, TABLE_NAME, CONSTRAINT_NAME) ");
        query.append(generateOptionalRefContraintsJoin());
        query.append(" LEFT JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC ON (A.REFERENCED_TABLE_SCHEMA = TC.TABLE_SCHEMA");
        query.append("  AND A.REFERENCED_TABLE_NAME = TC.TABLE_NAME");
        query.append("  AND TC.CONSTRAINT_TYPE IN ('UNIQUE', 'PRIMARY KEY'))");
        query.append("WHERE B.CONSTRAINT_TYPE = 'FOREIGN KEY'");
        if (parentDbFilter != null) {
            query.append(" AND A.REFERENCED_TABLE_SCHEMA=?");
        }
        query.append(" AND A.REFERENCED_TABLE_NAME=?");
        if (foreignDbFilter != null) {
            query.append(" AND A.TABLE_SCHEMA = ?");
        }
        query.append(" AND A.TABLE_NAME=?");
        query.append(" ORDER BY FKTABLE_NAME, FKTABLE_NAME, KEY_SEQ");

        PreparedStatement pStmt = null;

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            if (parentDbFilter != null) {
                pStmt.setString(nextId++, parentDbFilter);
            }
            pStmt.setString(nextId++, parentTableFilter);
            if (foreignDbFilter != null) {
                pStmt.setString(nextId++, foreignDbFilter);
            }
            pStmt.setString(nextId, foreignTableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createFkMetadataFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String dbFromTerm = getDatabase(catalog, schema);
        final String dbFilter = this.pedantic ? dbFromTerm : StringUtils.unQuoteIdentifier(dbFromTerm, this.quotedId);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);

        StringBuilder query = new StringBuilder(
                dbMapsToSchema ? "SELECT DISTINCT A.CONSTRAINT_CATALOG AS PKTABLE_CAT, A.REFERENCED_TABLE_SCHEMA AS PKTABLE_SCHEM,"
                        : "SELECT DISTINCT A.REFERENCED_TABLE_SCHEMA AS PKTABLE_CAT,NULL AS PKTABLE_SCHEM,");
        query.append(" A.REFERENCED_TABLE_NAME AS PKTABLE_NAME, A.REFERENCED_COLUMN_NAME AS PKCOLUMN_NAME,");
        query.append(dbMapsToSchema ? " A.TABLE_CATALOG AS FKTABLE_CAT, A.TABLE_SCHEMA AS FKTABLE_SCHEM,"
                : " A.TABLE_SCHEMA AS FKTABLE_CAT, NULL AS FKTABLE_SCHEM,");
        query.append(" A.TABLE_NAME AS FKTABLE_NAME, A.COLUMN_NAME AS FKCOLUMN_NAME, A.ORDINAL_POSITION AS KEY_SEQ,");
        query.append(generateUpdateRuleClause());
        query.append(" AS UPDATE_RULE,");
        query.append(generateDeleteRuleClause());
        query.append(" AS DELETE_RULE, A.CONSTRAINT_NAME AS FK_NAME, TC.CONSTRAINT_NAME AS PK_NAME,");
        query.append(importedKeyNotDeferrable);
        query.append(" AS DEFERRABILITY FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE A");
        query.append(" JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS B USING (TABLE_SCHEMA, TABLE_NAME, CONSTRAINT_NAME) ");
        query.append(generateOptionalRefContraintsJoin());
        query.append(" LEFT JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC ON (A.REFERENCED_TABLE_SCHEMA = TC.TABLE_SCHEMA");
        query.append("  AND A.REFERENCED_TABLE_NAME = TC.TABLE_NAME");
        query.append("  AND TC.CONSTRAINT_TYPE IN ('UNIQUE', 'PRIMARY KEY'))");
        query.append(" WHERE B.CONSTRAINT_TYPE = 'FOREIGN KEY'");
        if (dbFilter != null) {
            query.append(" AND A.REFERENCED_TABLE_SCHEMA = ?");
        }
        query.append(" AND A.REFERENCED_TABLE_NAME=?");
        query.append(" ORDER BY FKTABLE_NAME, FKTABLE_NAME, KEY_SEQ");

        PreparedStatement pStmt = null;

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, dbFilter);
            }
            pStmt.setString(nextId, tableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createFkMetadataFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    private String generateOptionalRefContraintsJoin() {
        return "JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS R ON (R.CONSTRAINT_NAME = B.CONSTRAINT_NAME "
                + "AND R.TABLE_NAME = B.TABLE_NAME AND R.CONSTRAINT_SCHEMA = B.TABLE_SCHEMA) ";
    }

    private String generateDeleteRuleClause() {
        return "CASE WHEN R.DELETE_RULE='CASCADE' THEN " + String.valueOf(importedKeyCascade) + " WHEN R.DELETE_RULE='SET NULL' THEN "
                + String.valueOf(importedKeySetNull) + " WHEN R.DELETE_RULE='SET DEFAULT' THEN " + String.valueOf(importedKeySetDefault)
                + " WHEN R.DELETE_RULE='RESTRICT' THEN " + String.valueOf(importedKeyRestrict) + " WHEN R.DELETE_RULE='NO ACTION' THEN "
                + String.valueOf(importedKeyRestrict) + " ELSE " + String.valueOf(importedKeyRestrict) + " END ";
    }

    private String generateUpdateRuleClause() {
        return "CASE WHEN R.UPDATE_RULE='CASCADE' THEN " + String.valueOf(importedKeyCascade) + " WHEN R.UPDATE_RULE='SET NULL' THEN "
                + String.valueOf(importedKeySetNull) + " WHEN R.UPDATE_RULE='SET DEFAULT' THEN " + String.valueOf(importedKeySetDefault)
                + " WHEN R.UPDATE_RULE='RESTRICT' THEN " + String.valueOf(importedKeyRestrict) + " WHEN R.UPDATE_RULE='NO ACTION' THEN "
                + String.valueOf(importedKeyRestrict) + " ELSE " + String.valueOf(importedKeyRestrict) + " END ";
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String dbFromTerm = getDatabase(catalog, schema);
        final String dbFilter = this.pedantic ? dbFromTerm : StringUtils.unQuoteIdentifier(dbFromTerm, this.quotedId);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);

        StringBuilder query = new StringBuilder(
                dbMapsToSchema ? "SELECT DISTINCT A.CONSTRAINT_CATALOG AS PKTABLE_CAT, A.REFERENCED_TABLE_SCHEMA AS PKTABLE_SCHEM,"
                        : "SELECT DISTINCT A.REFERENCED_TABLE_SCHEMA AS PKTABLE_CAT,NULL AS PKTABLE_SCHEM,");
        query.append(" A.REFERENCED_TABLE_NAME AS PKTABLE_NAME, A.REFERENCED_COLUMN_NAME AS PKCOLUMN_NAME,");
        query.append(dbMapsToSchema ? " A.TABLE_CATALOG AS FKTABLE_CAT, A.TABLE_SCHEMA AS FKTABLE_SCHEM,"
                : " A.TABLE_SCHEMA AS FKTABLE_CAT, NULL AS FKTABLE_SCHEM,");
        query.append(" A.TABLE_NAME AS FKTABLE_NAME, A.COLUMN_NAME AS FKCOLUMN_NAME, A.ORDINAL_POSITION AS KEY_SEQ,");
        query.append(generateUpdateRuleClause());
        query.append(" AS UPDATE_RULE,");
        query.append(generateDeleteRuleClause());
        query.append(" AS DELETE_RULE, A.CONSTRAINT_NAME AS FK_NAME, R.UNIQUE_CONSTRAINT_NAME AS PK_NAME,");
        query.append(importedKeyNotDeferrable);
        query.append(" AS DEFERRABILITY FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE A");
        query.append(" JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS B USING (CONSTRAINT_SCHEMA, CONSTRAINT_NAME, TABLE_NAME) ");
        query.append(generateOptionalRefContraintsJoin());
        query.append("WHERE B.CONSTRAINT_TYPE = 'FOREIGN KEY'");
        if (dbFilter != null) {
            query.append(" AND A.TABLE_SCHEMA = ?");
        }
        query.append(" AND A.TABLE_NAME=?");
        query.append(" AND A.REFERENCED_TABLE_SCHEMA IS NOT NULL");
        query.append(" ORDER BY A.REFERENCED_TABLE_SCHEMA, A.REFERENCED_TABLE_NAME, A.ORDINAL_POSITION");

        PreparedStatement pStmt = null;

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, dbFilter);
            }
            pStmt.setString(nextId, tableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createFkMetadataFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String dbFromTerm = getDatabase(catalog, schema);
        final String dbFilter = this.pedantic ? dbFromTerm : StringUtils.unQuoteIdentifier(dbFromTerm, this.quotedId);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);

        StringBuilder query = new StringBuilder(
                dbMapsToSchema ? "SELECT TABLE_CATALOG AS TABLE_CAT, TABLE_SCHEMA AS TABLE_SCHEM," : "SELECT TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM,");
        query.append(" TABLE_NAME, NON_UNIQUE, NULL AS INDEX_QUALIFIER, INDEX_NAME,");
        query.append(tableIndexOther);
        query.append(" AS TYPE, SEQ_IN_INDEX AS ORDINAL_POSITION, COLUMN_NAME,");
        query.append("COLLATION AS ASC_OR_DESC, CARDINALITY, 0 AS PAGES, NULL AS FILTER_CONDITION FROM INFORMATION_SCHEMA.STATISTICS WHERE");
        if (dbFilter != null) {
            query.append(" TABLE_SCHEMA = ? AND");
        }
        query.append(" TABLE_NAME = ?");
        if (unique) {
            query.append(" AND NON_UNIQUE=0 ");
        }
        query.append(" ORDER BY NON_UNIQUE, INDEX_NAME, SEQ_IN_INDEX");

        PreparedStatement pStmt = null;

        try {

            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, dbFilter);
            }
            pStmt.setString(nextId, tableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createIndexInfoFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String dbFromTerm = getDatabase(catalog, schema);
        final String dbFilter = this.pedantic ? dbFromTerm : StringUtils.unQuoteIdentifier(dbFromTerm, this.quotedId);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);

        StringBuilder query = new StringBuilder(
                dbMapsToSchema ? "SELECT TABLE_CATALOG AS TABLE_CAT, TABLE_SCHEMA AS TABLE_SCHEM," : "SELECT TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM,");
        query.append(" TABLE_NAME, COLUMN_NAME, SEQ_IN_INDEX AS KEY_SEQ, 'PRIMARY' AS PK_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE");
        if (dbFilter != null) {
            query.append(" TABLE_SCHEMA = ? AND");
        }
        query.append(" TABLE_NAME = ?");
        query.append(" AND INDEX_NAME='PRIMARY' ORDER BY TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, SEQ_IN_INDEX");

        PreparedStatement pStmt = null;

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, dbFilter);
            }
            pStmt.setString(nextId, tableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(getPrimaryKeysFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        String dbFilter = getDatabase(catalog, schemaPattern);
        dbFilter = this.pedantic ? dbFilter : StringUtils.unQuoteIdentifier(dbFilter, this.quotedId);
        final String tableNameFilter = this.pedantic ? tableNamePattern : StringUtils.unQuoteIdentifier(tableNamePattern, this.quotedId);

        PreparedStatement pStmt = null;

        StringBuilder query = new StringBuilder(
                dbMapsToSchema ? "SELECT TABLE_CATALOG AS TABLE_CAT, TABLE_SCHEMA AS TABLE_SCHEM," : "SELECT TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM,");
        query.append(" TABLE_NAME, CASE WHEN TABLE_TYPE='BASE TABLE' THEN ");
        query.append("CASE WHEN TABLE_SCHEMA='mysql' OR TABLE_SCHEMA='performance_schema' OR TABLE_SCHEMA='sys' THEN 'SYSTEM TABLE' ELSE 'TABLE' END ");
        query.append("WHEN TABLE_TYPE='TEMPORARY' THEN 'LOCAL_TEMPORARY' ELSE TABLE_TYPE END AS TABLE_TYPE, ");
        query.append("TABLE_COMMENT AS REMARKS, NULL AS TYPE_CAT, NULL AS TYPE_SCHEM, NULL AS TYPE_NAME, NULL AS SELF_REFERENCING_COL_NAME, ");
        query.append("NULL AS REF_GENERATION FROM INFORMATION_SCHEMA.TABLES");

        if (dbFilter != null || tableNameFilter != null) {
            query.append(" WHERE");
        }

        if (dbFilter != null) {
            query.append(dbMapsToSchema && StringUtils.hasWildcards(dbFilter) ? " TABLE_SCHEMA LIKE ?" : " TABLE_SCHEMA = ?");
        }

        if (tableNameFilter != null) {
            if (dbFilter != null) {
                query.append(" AND");
            }
            query.append(StringUtils.hasWildcards(tableNameFilter) ? " TABLE_NAME LIKE ?" : " TABLE_NAME = ?");
        }
        if (types != null && types.length > 0) {
            query.append(" HAVING TABLE_TYPE IN (?,?,?,?,?)");
        }
        query.append(" ORDER BY TABLE_TYPE, TABLE_SCHEMA, TABLE_NAME");
        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());

            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, dbFilter != null ? dbFilter : "%");
            }
            if (tableNameFilter != null) {
                pStmt.setString(nextId++, tableNameFilter);
            }

            if (types != null && types.length > 0) {
                for (int i = 0; i < 5; i++) {
                    pStmt.setNull(nextId + i, MysqlType.VARCHAR.getJdbcType());
                }
                for (int i = 0; i < types.length; i++) {
                    TableType tableType = TableType.getTableTypeEqualTo(types[i]);
                    if (tableType != TableType.UNKNOWN) {
                        pStmt.setString(nextId++, tableType.getName());
                    }
                }
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).setColumnDefinition(createTablesFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFromTerm = getDatabase(catalog, schema);
        final String dbFilter = this.pedantic ? dbFromTerm : StringUtils.unQuoteIdentifier(dbFromTerm, this.quotedId);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);

        StringBuilder query = new StringBuilder("SELECT NULL AS SCOPE, COLUMN_NAME, ");
        appendJdbcTypeMappingQuery(query, "DATA_TYPE", "COLUMN_TYPE");
        query.append(" AS DATA_TYPE, UPPER(COLUMN_TYPE) AS TYPE_NAME,");
        query.append(" CASE WHEN LCASE(DATA_TYPE)='date' THEN 10");
        if (this.conn.getServerVersion().meetsMinimum(ServerVersion.parseVersion("5.6.4"))) {
            query.append(" WHEN LCASE(DATA_TYPE)='time'");
            query.append("  THEN 8+(CASE WHEN DATETIME_PRECISION>0 THEN DATETIME_PRECISION+1 ELSE DATETIME_PRECISION END)");
            query.append(" WHEN LCASE(DATA_TYPE)='datetime' OR LCASE(DATA_TYPE)='timestamp'");
            query.append("  THEN 19+(CASE WHEN DATETIME_PRECISION>0 THEN DATETIME_PRECISION+1 ELSE DATETIME_PRECISION END)");
        } else {
            query.append(" WHEN LCASE(DATA_TYPE)='time' THEN 8");
            query.append(" WHEN LCASE(DATA_TYPE)='datetime' OR LCASE(DATA_TYPE)='timestamp' THEN 19");
        }
        query.append(" WHEN CHARACTER_MAXIMUM_LENGTH IS NULL THEN NUMERIC_PRECISION WHEN CHARACTER_MAXIMUM_LENGTH > ");
        query.append(Integer.MAX_VALUE);
        query.append(" THEN ");
        query.append(Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_MAXIMUM_LENGTH END AS COLUMN_SIZE, ");
        query.append(maxBufferSize);
        query.append(" AS BUFFER_LENGTH,NUMERIC_SCALE AS DECIMAL_DIGITS, ");
        query.append(Integer.toString(java.sql.DatabaseMetaData.versionColumnNotPseudo));
        query.append(" AS PSEUDO_COLUMN FROM INFORMATION_SCHEMA.COLUMNS WHERE");
        if (dbFilter != null) {
            query.append(" TABLE_SCHEMA = ? AND");
        }
        query.append(" TABLE_NAME = ?");
        query.append(" AND EXTRA LIKE '%on update CURRENT_TIMESTAMP%'");

        PreparedStatement pStmt = null;

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, dbFilter);
            }
            pStmt.setString(nextId, tableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(getVersionColumnsFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        String dbFilter = getDatabase(catalog, schemaPattern);
        dbFilter = this.pedantic ? dbFilter : StringUtils.unQuoteIdentifier(dbFilter, this.quotedId);
        final String procedureNameFilter = this.pedantic ? procedureNamePattern : StringUtils.unQuoteIdentifier(procedureNamePattern, this.quotedId);

        StringBuilder query = new StringBuilder(dbMapsToSchema ? "SELECT ROUTINE_CATALOG AS PROCEDURE_CAT, ROUTINE_SCHEMA AS PROCEDURE_SCHEM,"
                : "SELECT ROUTINE_SCHEMA AS PROCEDURE_CAT, NULL AS PROCEDURE_SCHEM,");
        query.append(
                " ROUTINE_NAME AS PROCEDURE_NAME, NULL AS RESERVED_1, NULL AS RESERVED_2, NULL AS RESERVED_3, ROUTINE_COMMENT AS REMARKS, CASE WHEN ROUTINE_TYPE = 'PROCEDURE' THEN ");
        query.append(procedureNoResult);
        query.append(" WHEN ROUTINE_TYPE='FUNCTION' THEN ");
        query.append(procedureReturnsResult);
        query.append(" ELSE ");
        query.append(procedureResultUnknown);
        query.append(" END AS PROCEDURE_TYPE, ROUTINE_NAME AS SPECIFIC_NAME FROM INFORMATION_SCHEMA.ROUTINES");

        StringBuilder condition = new StringBuilder();
        if (!this.conn.getPropertySet().getBooleanProperty(PropertyKey.getProceduresReturnsFunctions).getValue()) {
            condition.append(" ROUTINE_TYPE = 'PROCEDURE'");
        }
        if (dbFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(dbMapsToSchema ? " ROUTINE_SCHEMA LIKE ?" : " ROUTINE_SCHEMA = ?");
        }
        if (procedureNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" ROUTINE_NAME LIKE ?");
        }

        if (condition.length() > 0) {
            query.append(" WHERE");
            query.append(condition);
        }
        query.append(" ORDER BY ROUTINE_SCHEMA, ROUTINE_NAME, ROUTINE_TYPE");

        PreparedStatement pStmt = null;

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, dbFilter);
            }
            if (procedureNameFilter != null) {
                pStmt.setString(nextId, procedureNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createFieldMetadataForGetProcedures());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException {
        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        String dbFilter = getDatabase(catalog, schemaPattern);
        dbFilter = this.pedantic ? dbFilter : StringUtils.unQuoteIdentifier(dbFilter, this.quotedId);
        final String procedureNameFilter = this.pedantic ? procedureNamePattern : StringUtils.unQuoteIdentifier(procedureNamePattern, this.quotedId);
        final String columnNameFilter = this.pedantic ? columnNamePattern : StringUtils.unQuoteIdentifier(columnNamePattern, this.quotedId);

        final boolean supportsFractSeconds = this.conn.getServerVersion().meetsMinimum(ServerVersion.parseVersion("5.6.4"));

        StringBuilder query = new StringBuilder(dbMapsToSchema ? "SELECT SPECIFIC_CATALOG AS PROCEDURE_CAT, SPECIFIC_SCHEMA AS `PROCEDURE_SCHEM`,"
                : "SELECT SPECIFIC_SCHEMA AS PROCEDURE_CAT, NULL AS `PROCEDURE_SCHEM`,");
        query.append(" SPECIFIC_NAME AS `PROCEDURE_NAME`, IFNULL(PARAMETER_NAME, '') AS `COLUMN_NAME`,");
        query.append(" CASE WHEN PARAMETER_MODE = 'IN' THEN ");
        query.append(procedureColumnIn);
        query.append(" WHEN PARAMETER_MODE = 'OUT' THEN ");
        query.append(procedureColumnOut);
        query.append(" WHEN PARAMETER_MODE = 'INOUT' THEN ");
        query.append(procedureColumnInOut);
        query.append(" WHEN ORDINAL_POSITION = 0 THEN ");
        query.append(procedureColumnReturn);
        query.append(" ELSE ");
        query.append(procedureColumnUnknown);
        query.append(" END AS `COLUMN_TYPE`, ");
        appendJdbcTypeMappingQuery(query, "DATA_TYPE", "DTD_IDENTIFIER");
        query.append(" AS `DATA_TYPE`, ");

        query.append("UPPER(CASE");
        if (this.tinyInt1isBit) {
            query.append(" WHEN UPPER(DATA_TYPE)='TINYINT' THEN CASE");
            query.append(
                    " WHEN LOCATE('ZEROFILL', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('(1)', DTD_IDENTIFIER) != 0 THEN ");
            query.append(this.transformedBitIsBoolean ? "'BOOLEAN'" : "'BIT'");
            query.append(" WHEN LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) != 0 AND LOCATE('UNSIGNED', UPPER(DATA_TYPE)) = 0 THEN 'TINYINT UNSIGNED'");
            query.append(" ELSE DATA_TYPE END ");
        }
        query.append(
                " WHEN LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) != 0 AND LOCATE('UNSIGNED', UPPER(DATA_TYPE)) = 0 AND LOCATE('SET', UPPER(DATA_TYPE)) <> 1 AND LOCATE('ENUM', UPPER(DATA_TYPE)) <> 1 THEN CONCAT(DATA_TYPE, ' UNSIGNED')");

        // spatial data types
        query.append(" WHEN UPPER(DATA_TYPE)='POINT' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='LINESTRING' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='POLYGON' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTIPOINT' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTILINESTRING' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTIPOLYGON' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='GEOMETRYCOLLECTION' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='GEOMCOLLECTION' THEN 'GEOMETRY'");

        query.append(" ELSE UPPER(DATA_TYPE) END) AS TYPE_NAME,");

        // PRECISION
        query.append(" CASE WHEN LCASE(DATA_TYPE)='date' THEN 0");
        if (supportsFractSeconds) {
            query.append(" WHEN LCASE(DATA_TYPE)='time' OR LCASE(DATA_TYPE)='datetime' OR LCASE(DATA_TYPE)='timestamp' THEN DATETIME_PRECISION");
        } else {
            query.append(" WHEN LCASE(DATA_TYPE)='time' OR LCASE(DATA_TYPE)='datetime' OR LCASE(DATA_TYPE)='timestamp' THEN 0");
        }
        if (this.tinyInt1isBit && !this.transformedBitIsBoolean) {
            query.append(
                    " WHEN (UPPER(DATA_TYPE)='TINYINT' AND LOCATE('ZEROFILL', UPPER(DTD_IDENTIFIER)) = 0) AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('(1)', DTD_IDENTIFIER) != 0 THEN 1");
        }
        // workaround for Bug#69042 (16712664), "MEDIUMINT PRECISION/TYPE INCORRECT IN INFORMATION_SCHEMA.COLUMNS", I_S bug returns NUMERIC_PRECISION=7 for MEDIUMINT UNSIGNED when it must be 8.
        query.append(" WHEN UPPER(DATA_TYPE)='MEDIUMINT' AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) != 0 THEN 8");
        query.append(" WHEN UPPER(DATA_TYPE)='JSON' THEN 1073741824"); // JSON columns is limited to the value of the max_allowed_packet system variable (max value 1073741824)
        query.append(" ELSE NUMERIC_PRECISION END AS `PRECISION`,"); //

        // LENGTH
        query.append(" CASE WHEN LCASE(DATA_TYPE)='date' THEN 10");
        if (supportsFractSeconds) {
            query.append(" WHEN LCASE(DATA_TYPE)='time' THEN 8+(CASE WHEN DATETIME_PRECISION>0 THEN DATETIME_PRECISION+1 ELSE DATETIME_PRECISION END)");
            query.append(" WHEN LCASE(DATA_TYPE)='datetime' OR LCASE(DATA_TYPE)='timestamp'");
            query.append("  THEN 19+(CASE WHEN DATETIME_PRECISION>0 THEN DATETIME_PRECISION+1 ELSE DATETIME_PRECISION END)");
        } else {
            query.append(" WHEN LCASE(DATA_TYPE)='time' THEN 8");
            query.append(" WHEN LCASE(DATA_TYPE)='datetime' OR LCASE(DATA_TYPE)='timestamp' THEN 19");
        }
        if (this.tinyInt1isBit && !this.transformedBitIsBoolean) {
            query.append(
                    " WHEN (UPPER(DATA_TYPE)='TINYINT' OR UPPER(DATA_TYPE)='TINYINT UNSIGNED') AND LOCATE('ZEROFILL', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('(1)', DTD_IDENTIFIER) != 0 THEN 1");
        }
        // workaround for Bug#69042 (16712664), "MEDIUMINT PRECISION/TYPE INCORRECT IN INFORMATION_SCHEMA.COLUMNS", I_S bug returns NUMERIC_PRECISION=7 for MEDIUMINT UNSIGNED when it must be 8.
        query.append(" WHEN UPPER(DATA_TYPE)='MEDIUMINT' AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) != 0 THEN 8");
        query.append(" WHEN UPPER(DATA_TYPE)='JSON' THEN 1073741824"); // JSON columns is limited to the value of the max_allowed_packet system variable (max value 1073741824)
        query.append(" WHEN CHARACTER_MAXIMUM_LENGTH IS NULL THEN NUMERIC_PRECISION");
        query.append(" WHEN CHARACTER_MAXIMUM_LENGTH > ");
        query.append(Integer.MAX_VALUE);
        query.append(" THEN ");
        query.append(Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_MAXIMUM_LENGTH END AS LENGTH,"); //

        query.append("NUMERIC_SCALE AS `SCALE`, ");
        query.append("10 AS RADIX,");
        query.append(procedureNullable);
        query.append(" AS `NULLABLE`, NULL AS `REMARKS`, NULL AS `COLUMN_DEF`, NULL AS `SQL_DATA_TYPE`, NULL AS `SQL_DATETIME_SUB`,");

        query.append(" CASE WHEN CHARACTER_OCTET_LENGTH > ");
        query.append(Integer.MAX_VALUE);
        query.append(" THEN ");
        query.append(Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_OCTET_LENGTH END AS `CHAR_OCTET_LENGTH`,"); //

        query.append(" ORDINAL_POSITION, 'YES' AS `IS_NULLABLE`, SPECIFIC_NAME");
        query.append(" FROM INFORMATION_SCHEMA.PARAMETERS");

        StringBuilder condition = new StringBuilder();
        if (!this.conn.getPropertySet().getBooleanProperty(PropertyKey.getProceduresReturnsFunctions).getValue()) {
            condition.append(" ROUTINE_TYPE = 'PROCEDURE'");
        }
        if (dbFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(dbMapsToSchema ? " SPECIFIC_SCHEMA LIKE ?" : " SPECIFIC_SCHEMA = ?");
        }
        if (procedureNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" SPECIFIC_NAME LIKE ?");
        }
        if (columnNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" (PARAMETER_NAME LIKE ? OR PARAMETER_NAME IS NULL)");
        }

        if (condition.length() > 0) {
            query.append(" WHERE");
            query.append(condition);
        }
        query.append(" ORDER BY SPECIFIC_SCHEMA, SPECIFIC_NAME, ROUTINE_TYPE, ORDINAL_POSITION");

        PreparedStatement pStmt = null;

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());

            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, dbFilter);
            }
            if (procedureNameFilter != null) {
                pStmt.setString(nextId++, procedureNameFilter);
            }
            if (columnNameFilter != null) {
                pStmt.setString(nextId, columnNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createProcedureColumnsFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    /**
     * Getter to DatabaseMetaData.function* constants.
     *
     * @param constant
     *            the constant id from DatabaseMetaData fields to return.
     *
     * @return one of the java.sql.DatabaseMetaData#function* fields.
     */
    protected int getFunctionConstant(FunctionConstant constant) {
        switch (constant) {
            case FUNCTION_COLUMN_IN:
                return functionColumnIn;
            case FUNCTION_COLUMN_INOUT:
                return functionColumnInOut;
            case FUNCTION_COLUMN_OUT:
                return functionColumnOut;
            case FUNCTION_COLUMN_RETURN:
                return functionReturn;
            case FUNCTION_COLUMN_RESULT:
                return functionColumnResult;
            case FUNCTION_COLUMN_UNKNOWN:
                return functionColumnUnknown;
            case FUNCTION_NO_NULLS:
                return functionNoNulls;
            case FUNCTION_NULLABLE:
                return functionNullable;
            case FUNCTION_NULLABLE_UNKNOWN:
                return functionNullableUnknown;
            default:
                return -1;
        }
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException {
        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        String dbFilter = getDatabase(catalog, schemaPattern);
        dbFilter = this.pedantic ? dbFilter : StringUtils.unQuoteIdentifier(dbFilter, this.quotedId);
        final String functionNameFilter = this.pedantic ? functionNamePattern : StringUtils.unQuoteIdentifier(functionNamePattern, this.quotedId);

        StringBuilder query = new StringBuilder(dbMapsToSchema ? "SELECT ROUTINE_CATALOG AS FUNCTION_CAT, ROUTINE_SCHEMA AS FUNCTION_SCHEM,"
                : "SELECT ROUTINE_SCHEMA AS FUNCTION_CAT, NULL AS FUNCTION_SCHEM,");
        query.append(" ROUTINE_NAME AS FUNCTION_NAME, ROUTINE_COMMENT AS REMARKS, ");
        query.append(functionNoTable);
        query.append(" AS FUNCTION_TYPE, ROUTINE_NAME AS SPECIFIC_NAME FROM INFORMATION_SCHEMA.ROUTINES");
        query.append(" WHERE ROUTINE_TYPE LIKE 'FUNCTION'");
        if (dbFilter != null) {
            query.append(dbMapsToSchema ? " AND ROUTINE_SCHEMA LIKE ?" : " AND ROUTINE_SCHEMA = ?");
        }
        if (functionNameFilter != null) {
            query.append(" AND ROUTINE_NAME LIKE ?");
        }
        query.append(" ORDER BY FUNCTION_CAT, FUNCTION_SCHEM, FUNCTION_NAME, SPECIFIC_NAME");

        PreparedStatement pStmt = null;

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, dbFilter);
            }
            if (functionNameFilter != null) {
                pStmt.setString(nextId, functionNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(getFunctionsFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException {
        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        String dbFilter = getDatabase(catalog, schemaPattern);
        dbFilter = this.pedantic ? dbFilter : StringUtils.unQuoteIdentifier(dbFilter, this.quotedId);
        final String functionNameFilter = this.pedantic ? functionNamePattern : StringUtils.unQuoteIdentifier(functionNamePattern, this.quotedId);
        final String columnNameFilter = this.pedantic ? columnNamePattern : StringUtils.unQuoteIdentifier(columnNamePattern, this.quotedId);

        boolean supportsFractSeconds = this.conn.getServerVersion().meetsMinimum(ServerVersion.parseVersion("5.6.4"));

        StringBuilder query = new StringBuilder(dbMapsToSchema ? "SELECT SPECIFIC_CATALOG AS FUNCTION_CAT, SPECIFIC_SCHEMA AS `FUNCTION_SCHEM`,"
                : "SELECT SPECIFIC_SCHEMA AS FUNCTION_CAT, NULL AS `FUNCTION_SCHEM`,");
        query.append(" SPECIFIC_NAME AS `FUNCTION_NAME`, IFNULL(PARAMETER_NAME, '') AS `COLUMN_NAME`, CASE WHEN PARAMETER_MODE = 'IN' THEN ");
        query.append(getFunctionConstant(FunctionConstant.FUNCTION_COLUMN_IN));
        query.append(" WHEN PARAMETER_MODE = 'OUT' THEN ");
        query.append(getFunctionConstant(FunctionConstant.FUNCTION_COLUMN_OUT));
        query.append(" WHEN PARAMETER_MODE = 'INOUT' THEN ");
        query.append(getFunctionConstant(FunctionConstant.FUNCTION_COLUMN_INOUT));
        query.append(" WHEN ORDINAL_POSITION = 0 THEN ");
        query.append(getFunctionConstant(FunctionConstant.FUNCTION_COLUMN_RETURN));
        query.append(" ELSE ");
        query.append(getFunctionConstant(FunctionConstant.FUNCTION_COLUMN_UNKNOWN));
        query.append(" END AS `COLUMN_TYPE`, ");
        appendJdbcTypeMappingQuery(query, "DATA_TYPE", "DTD_IDENTIFIER");
        query.append(" AS `DATA_TYPE`, ");
        query.append("UPPER(CASE");
        if (this.tinyInt1isBit) {
            query.append(" WHEN UPPER(DATA_TYPE)='TINYINT' THEN CASE");
            query.append(
                    " WHEN LOCATE('ZEROFILL', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('(1)', DTD_IDENTIFIER) != 0 THEN ");
            query.append(this.transformedBitIsBoolean ? "'BOOLEAN'" : "'BIT'");
            query.append(" WHEN LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) != 0 AND LOCATE('UNSIGNED', UPPER(DATA_TYPE)) = 0 THEN 'TINYINT UNSIGNED'");
            query.append(" ELSE DATA_TYPE END ");
        }
        query.append(
                " WHEN LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) != 0 AND LOCATE('UNSIGNED', UPPER(DATA_TYPE)) = 0 AND LOCATE('SET', UPPER(DATA_TYPE)) <> 1 AND LOCATE('ENUM', UPPER(DATA_TYPE)) <> 1 THEN CONCAT(DATA_TYPE, ' UNSIGNED')");

        // spatial data types
        query.append(" WHEN UPPER(DATA_TYPE)='POINT' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='LINESTRING' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='POLYGON' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTIPOINT' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTILINESTRING' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='MULTIPOLYGON' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='GEOMETRYCOLLECTION' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE)='GEOMCOLLECTION' THEN 'GEOMETRY'");

        query.append(" ELSE UPPER(DATA_TYPE) END) AS TYPE_NAME,");

        // PRECISION
        query.append(" CASE WHEN LCASE(DATA_TYPE)='date' THEN 0");
        if (supportsFractSeconds) {
            query.append(" WHEN LCASE(DATA_TYPE)='time' OR LCASE(DATA_TYPE)='datetime' OR LCASE(DATA_TYPE)='timestamp' THEN DATETIME_PRECISION");
        } else {
            query.append(" WHEN LCASE(DATA_TYPE)='time' OR LCASE(DATA_TYPE)='datetime' OR LCASE(DATA_TYPE)='timestamp' THEN 0");
        }
        if (this.tinyInt1isBit && !this.transformedBitIsBoolean) {
            query.append(
                    " WHEN UPPER(DATA_TYPE)='TINYINT' AND LOCATE('ZEROFILL', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('(1)', DTD_IDENTIFIER) != 0 THEN 1");
        }
        // workaround for Bug#69042 (16712664), "MEDIUMINT PRECISION/TYPE INCORRECT IN INFORMATION_SCHEMA.COLUMNS", I_S bug returns NUMERIC_PRECISION=7 for MEDIUMINT UNSIGNED when it must be 8.
        query.append(" WHEN UPPER(DATA_TYPE)='MEDIUMINT' AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) != 0 THEN 8");
        query.append(" WHEN UPPER(DATA_TYPE)='JSON' THEN 1073741824"); // JSON columns is limited to the value of the max_allowed_packet system variable (max value 1073741824)
        query.append(" ELSE NUMERIC_PRECISION END AS `PRECISION`,"); //

        // LENGTH
        query.append(" CASE WHEN LCASE(DATA_TYPE)='date' THEN 10");
        if (supportsFractSeconds) {
            query.append(" WHEN LCASE(DATA_TYPE)='time' THEN 8+(CASE WHEN DATETIME_PRECISION>0 THEN DATETIME_PRECISION+1 ELSE DATETIME_PRECISION END)");
            query.append(" WHEN LCASE(DATA_TYPE)='datetime' OR LCASE(DATA_TYPE)='timestamp'");
            query.append("  THEN 19+(CASE WHEN DATETIME_PRECISION>0 THEN DATETIME_PRECISION+1 ELSE DATETIME_PRECISION END)");
        } else {
            query.append(" WHEN LCASE(DATA_TYPE)='time' THEN 8");
            query.append(" WHEN LCASE(DATA_TYPE)='datetime' OR LCASE(DATA_TYPE)='timestamp' THEN 19");
        }
        if (this.tinyInt1isBit && !this.transformedBitIsBoolean) {
            query.append(
                    " WHEN (UPPER(DATA_TYPE)='TINYINT' OR UPPER(DATA_TYPE)='TINYINT UNSIGNED') AND LOCATE('ZEROFILL', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('(1)', DTD_IDENTIFIER) != 0 THEN 1");
        }
        // workaround for Bug#69042 (16712664), "MEDIUMINT PRECISION/TYPE INCORRECT IN INFORMATION_SCHEMA.COLUMNS", I_S bug returns NUMERIC_PRECISION=7 for MEDIUMINT UNSIGNED when it must be 8.
        query.append(" WHEN UPPER(DATA_TYPE)='MEDIUMINT' AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) != 0 THEN 8");
        query.append(" WHEN UPPER(DATA_TYPE)='JSON' THEN 1073741824"); // JSON columns is limited to the value of the max_allowed_packet system variable (max value 1073741824)
        query.append(" WHEN CHARACTER_MAXIMUM_LENGTH IS NULL THEN NUMERIC_PRECISION");
        query.append(" WHEN CHARACTER_MAXIMUM_LENGTH > " + Integer.MAX_VALUE + " THEN " + Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_MAXIMUM_LENGTH END AS LENGTH, ");

        query.append("NUMERIC_SCALE AS `SCALE`, 10 AS RADIX, ");
        query.append(getFunctionConstant(FunctionConstant.FUNCTION_NULLABLE));
        query.append(" AS `NULLABLE`,  NULL AS `REMARKS`,");

        query.append(" CASE WHEN CHARACTER_OCTET_LENGTH > ");
        query.append(Integer.MAX_VALUE);
        query.append(" THEN ");
        query.append(Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_OCTET_LENGTH END AS `CHAR_OCTET_LENGTH`,"); //

        query.append(" ORDINAL_POSITION, 'YES' AS `IS_NULLABLE`,");
        query.append(" SPECIFIC_NAME FROM INFORMATION_SCHEMA.PARAMETERS WHERE");

        StringBuilder condition = new StringBuilder();
        if (dbFilter != null) {
            condition.append(dbMapsToSchema ? " SPECIFIC_SCHEMA LIKE ?" : " SPECIFIC_SCHEMA = ?");
        }
        if (functionNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" SPECIFIC_NAME LIKE ?");
        }
        if (columnNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" (PARAMETER_NAME LIKE ? OR PARAMETER_NAME IS NULL)");
        }
        if (condition.length() > 0) {
            condition.append(" AND");
        }
        condition.append(" ROUTINE_TYPE='FUNCTION'");

        query.append(condition);
        query.append(" ORDER BY SPECIFIC_SCHEMA, SPECIFIC_NAME, ORDINAL_POSITION");

        PreparedStatement pStmt = null;

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, dbFilter);
            }
            if (functionNameFilter != null) {
                pStmt.setString(nextId++, functionNameFilter);
            }
            if (columnNameFilter != null) {
                pStmt.setString(nextId, columnNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createFunctionColumnsFields());
            return rs;
        } finally {
            if (pStmt != null) {
                pStmt.close();
            }
        }
    }

    @Override
    public String getSQLKeywords() throws SQLException {
        if (!this.conn.getServerVersion().meetsMinimum(ServerVersion.parseVersion("8.0.11"))) {
            return super.getSQLKeywords();
        }

        String keywords = keywordsCache.get(this.conn.getServerVersion());
        if (keywords != null) {
            return keywords;
        }

        KEYWORDS_CACHE_LOCK.lock();
        try {
            // Double check, maybe another thread already added it.
            keywords = keywordsCache.get(this.conn.getServerVersion());
            if (keywords != null) {
                return keywords;
            }

            List<String> keywordsFromServer = new ArrayList<>();
            Statement stmt = this.conn.getMetadataSafeStatement();
            ResultSet rs = stmt.executeQuery("SELECT WORD FROM INFORMATION_SCHEMA.KEYWORDS WHERE RESERVED=1 ORDER BY WORD");
            while (rs.next()) {
                keywordsFromServer.add(rs.getString(1));
            }
            stmt.close();

            keywordsFromServer.removeAll(SQL2003_KEYWORDS);
            keywords = String.join(",", keywordsFromServer);

            keywordsCache.put(this.conn.getServerVersion(), keywords);
            return keywords;
        } finally {
            KEYWORDS_CACHE_LOCK.unlock();
        }
    }

    private final void appendJdbcTypeMappingQuery(StringBuilder buf, String mysqlTypeColumnName, String fullMysqlTypeColumnName) {
        buf.append("CASE ");
        for (MysqlType mysqlType : MysqlType.values()) {

            buf.append(" WHEN UPPER(");
            buf.append(mysqlTypeColumnName);
            buf.append(")='");
            buf.append(mysqlType.getName());
            buf.append("' THEN ");

            switch (mysqlType) {
                case TINYINT:
                case TINYINT_UNSIGNED:
                    if (this.tinyInt1isBit) {
                        buf.append("CASE");
                        buf.append(" WHEN LOCATE('ZEROFILL', UPPER(");
                        buf.append(fullMysqlTypeColumnName);
                        buf.append(")) = 0 AND LOCATE('UNSIGNED', UPPER(");
                        buf.append(fullMysqlTypeColumnName);
                        buf.append(")) = 0 AND LOCATE('(1)', ");
                        buf.append(fullMysqlTypeColumnName);
                        buf.append(") != 0 THEN ");
                        buf.append(this.transformedBitIsBoolean ? "16" : "-7");
                        buf.append(" ELSE -6 END ");
                    } else {
                        buf.append(mysqlType.getJdbcType());
                    }
                    break;
                case YEAR:
                    buf.append(this.yearIsDateType ? mysqlType.getJdbcType() : Types.SMALLINT);
                    break;
                default:
                    buf.append(mysqlType.getJdbcType());
            }
        }

        buf.append(" WHEN UPPER(DATA_TYPE)='POINT' THEN -2");
        buf.append(" WHEN UPPER(DATA_TYPE)='LINESTRING' THEN -2");
        buf.append(" WHEN UPPER(DATA_TYPE)='POLYGON' THEN -2");
        buf.append(" WHEN UPPER(DATA_TYPE)='MULTIPOINT' THEN -2");
        buf.append(" WHEN UPPER(DATA_TYPE)='MULTILINESTRING' THEN -2");
        buf.append(" WHEN UPPER(DATA_TYPE)='MULTIPOLYGON' THEN -2");
        buf.append(" WHEN UPPER(DATA_TYPE)='GEOMETRYCOLLECTION' THEN -2");
        buf.append(" WHEN UPPER(DATA_TYPE)='GEOMCOLLECTION' THEN -2");

        buf.append(" ELSE 1111");
        buf.append(" END ");
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        // TODO Implement with I_S
        return super.getSchemas();
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        // TODO Implement with I_S
        return super.getSchemas(catalog, schemaPattern);
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        // TODO Implement with I_S
        return super.getCatalogs();
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        // TODO Implement with I_S
        return super.getTablePrivileges(catalog, schemaPattern, tableNamePattern);
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException {
        // TODO Implement with I_S
        return super.getBestRowIdentifier(catalog, schema, table, scope, nullable);
    }

}
