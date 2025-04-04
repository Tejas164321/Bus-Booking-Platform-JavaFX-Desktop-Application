/*
 * Copyright (c) 2002, 2024, Oracle and/or its affiliates.
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

import static com.mysql.cj.jdbc.DatabaseMetaData.ProcedureType.FUNCTION;
import static com.mysql.cj.jdbc.DatabaseMetaData.ProcedureType.PROCEDURE;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.mysql.cj.Constants;
import com.mysql.cj.Messages;
import com.mysql.cj.MysqlType;
import com.mysql.cj.NativeSession;
import com.mysql.cj.QueryInfo;
import com.mysql.cj.conf.PropertyDefinitions.DatabaseTerm;
import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.conf.RuntimeProperty;
import com.mysql.cj.exceptions.AssertionFailedException;
import com.mysql.cj.exceptions.CJException;
import com.mysql.cj.exceptions.ExceptionInterceptor;
import com.mysql.cj.exceptions.MysqlErrorNumbers;
import com.mysql.cj.jdbc.exceptions.SQLError;
import com.mysql.cj.jdbc.exceptions.SQLExceptionsMapping;
import com.mysql.cj.jdbc.result.ResultSetFactory;
import com.mysql.cj.protocol.ColumnDefinition;
import com.mysql.cj.protocol.a.result.ByteArrayRow;
import com.mysql.cj.protocol.a.result.ResultsetRowsStatic;
import com.mysql.cj.result.DefaultColumnDefinition;
import com.mysql.cj.result.Field;
import com.mysql.cj.result.Row;
import com.mysql.cj.telemetry.TelemetryAttribute;
import com.mysql.cj.telemetry.TelemetryScope;
import com.mysql.cj.telemetry.TelemetrySpan;
import com.mysql.cj.telemetry.TelemetrySpanName;
import com.mysql.cj.util.SearchMode;
import com.mysql.cj.util.StringUtils;

/**
 * JDBC Interface to Mysql functions
 * <p>
 * This class provides information about the database as a whole.
 * </p>
 * <p>
 * Many of the methods here return lists of information in ResultSets. You can use the normal ResultSet methods such as getString and getInt to retrieve the
 * data from these ResultSets. If a given form of metadata is not available, these methods show throw a SQLException.
 * </p>
 * <p>
 * Some of these methods take arguments that are String patterns. These methods all have names such as fooPattern. Within a pattern String "%" means match any
 * substring of 0 or more characters and "_" means match any one character.
 * </p>
 */
public class DatabaseMetaData implements java.sql.DatabaseMetaData {

    private static final Lock LOCK = new ReentrantLock();

    /**
     * Default max buffer size. See {@link PropertyKey#maxAllowedPacket}.
     */
    protected static int maxBufferSize = 65535; // TODO find a way to use actual (not default) value

    protected abstract class IteratorWithCleanup<T> {

        abstract void close() throws SQLException;

        abstract boolean hasNext() throws SQLException;

        abstract T next() throws SQLException;

    }

    class ReferencingAndReferencedColumns {

        final List<String> referencingColumnsList;
        final List<String> referencedColumnsList;
        final String constraintName;
        final String referencedDatabase;
        final String referencedTable;

        ReferencingAndReferencedColumns(List<String> localColumns, List<String> refColumns, String constName, String refDatabase, String refTable) {
            this.referencingColumnsList = localColumns;
            this.referencedColumnsList = refColumns;
            this.constraintName = constName;
            this.referencedTable = refTable;
            this.referencedDatabase = refDatabase;
        }

    }

    protected class StringListIterator extends IteratorWithCleanup<String> {

        int idx = -1;
        List<String> list;

        StringListIterator(List<String> list) {
            this.list = list;
        }

        @Override
        void close() throws SQLException {
            this.list = null;
        }

        @Override
        boolean hasNext() throws SQLException {
            return this.idx < this.list.size() - 1;
        }

        @Override
        String next() throws SQLException {
            this.idx++;
            return this.list.get(this.idx);
        }

    }

    protected class SingleStringIterator extends IteratorWithCleanup<String> {

        boolean onFirst = true;
        String value;

        SingleStringIterator(String s) {
            this.value = s;
        }

        @Override
        void close() throws SQLException {
            // not needed
        }

        @Override
        boolean hasNext() throws SQLException {
            return this.onFirst;
        }

        @Override
        String next() throws SQLException {
            this.onFirst = false;
            return this.value;
        }

    }

    /**
     * Parses and represents common data type information used by various column/parameter methods.
     */
    class TypeDescriptor {

        int bufferLength;
        Integer datetimePrecision = null;
        Integer columnSize = null;
        Integer charOctetLength = null;
        Integer decimalDigits = null;
        String isNullable;
        int nullability;
        int numPrecRadix = 10;
        String mysqlTypeName;
        MysqlType mysqlType;

        TypeDescriptor(String typeInfo, String nullabilityInfo) throws SQLException {
            if (typeInfo == null) {
                throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.0"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                        getExceptionInterceptor());
            }

            this.mysqlType = MysqlType.getByName(typeInfo);

            // Figure Out the Size

            String temp;
            java.util.StringTokenizer tokenizer;
            int maxLength = 0;
            int fract;

            switch (this.mysqlType) {
                case ENUM:
                    temp = typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.lastIndexOf(")"));
                    tokenizer = new java.util.StringTokenizer(temp, ",");
                    while (tokenizer.hasMoreTokens()) {
                        String nextToken = tokenizer.nextToken();
                        maxLength = Math.max(maxLength, nextToken.length() - 2);
                    }
                    this.columnSize = Integer.valueOf(maxLength);
                    break;

                case SET:
                    temp = typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.lastIndexOf(")"));
                    tokenizer = new java.util.StringTokenizer(temp, ",");

                    int numElements = tokenizer.countTokens();
                    if (numElements > 0) {
                        maxLength += numElements - 1;
                    }

                    while (tokenizer.hasMoreTokens()) {
                        String setMember = tokenizer.nextToken().trim();

                        if (setMember.startsWith("'") && setMember.endsWith("'")) {
                            maxLength += setMember.length() - 2;
                        } else {
                            maxLength += setMember.length();
                        }
                    }
                    this.columnSize = Integer.valueOf(maxLength);
                    break;

                case FLOAT:
                case FLOAT_UNSIGNED:
                    if (typeInfo.indexOf(",") != -1) {
                        // Numeric with decimals
                        this.columnSize = Integer.valueOf(typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.indexOf(",")).trim());
                        this.decimalDigits = Integer.valueOf(typeInfo.substring(typeInfo.indexOf(",") + 1, typeInfo.indexOf(")")).trim());
                    } else if (typeInfo.indexOf("(") != -1) {
                        int size = Integer.parseInt(typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.indexOf(")")).trim());
                        if (size > 23) {
                            this.mysqlType = this.mysqlType == MysqlType.FLOAT ? MysqlType.DOUBLE : MysqlType.DOUBLE_UNSIGNED;
                            this.columnSize = Integer.valueOf(22);
                            this.decimalDigits = 0;
                        }
                    } else {
                        this.columnSize = Integer.valueOf(12);
                        this.decimalDigits = 0;
                    }
                    break;
                case DECIMAL:
                case DECIMAL_UNSIGNED:
                case DOUBLE:
                case DOUBLE_UNSIGNED:
                    if (typeInfo.indexOf(",") != -1) {
                        // Numeric with decimals
                        this.columnSize = Integer.valueOf(typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.indexOf(",")).trim());
                        this.decimalDigits = Integer.valueOf(typeInfo.substring(typeInfo.indexOf(",") + 1, typeInfo.indexOf(")")).trim());
                    } else {
                        switch (this.mysqlType) {
                            case DECIMAL:
                            case DECIMAL_UNSIGNED:
                                this.columnSize = Integer.valueOf(65);
                                break;
                            case DOUBLE:
                            case DOUBLE_UNSIGNED:
                                this.columnSize = Integer.valueOf(22);
                                break;
                            default:
                                break;
                        }
                        this.decimalDigits = 0;
                    }
                    break;

                case CHAR:
                case VARCHAR:
                case TINYTEXT:
                case MEDIUMTEXT:
                case LONGTEXT:
                case JSON:
                case TEXT:
                case TINYBLOB:
                case MEDIUMBLOB:
                case LONGBLOB:
                case BLOB:
                case BINARY:
                case VARBINARY:
                case BIT:
                    if (this.mysqlType == MysqlType.CHAR) {
                        this.columnSize = Integer.valueOf(1);
                    }
                    if (typeInfo.indexOf("(") != -1) {
                        int endParenIndex = typeInfo.indexOf(")");

                        if (endParenIndex == -1) {
                            endParenIndex = typeInfo.length();
                        }

                        this.columnSize = Integer.valueOf(typeInfo.substring(typeInfo.indexOf("(") + 1, endParenIndex).trim());

                        // Adjust for pseudo-boolean
                        if (DatabaseMetaData.this.tinyInt1isBit && this.columnSize.intValue() == 1 && StringUtils.startsWithIgnoreCase(typeInfo, "tinyint")) {
                            if (DatabaseMetaData.this.transformedBitIsBoolean) {
                                this.mysqlType = MysqlType.BOOLEAN;
                            } else {
                                this.mysqlType = MysqlType.BIT;
                            }
                        }
                    }

                    break;

                case TINYINT:
                    if (DatabaseMetaData.this.tinyInt1isBit && typeInfo.indexOf("(1)") != -1) {
                        if (DatabaseMetaData.this.transformedBitIsBoolean) {
                            this.mysqlType = MysqlType.BOOLEAN;
                        } else {
                            this.mysqlType = MysqlType.BIT;
                        }
                    } else {
                        this.columnSize = Integer.valueOf(3);
                    }
                    break;

                case TINYINT_UNSIGNED:
                    this.columnSize = Integer.valueOf(3);
                    break;

                case DATE:
                    this.datetimePrecision = 0;
                    this.columnSize = 10;
                    break;

                case TIME:
                    this.datetimePrecision = 0;
                    this.columnSize = 8;
                    if (typeInfo.indexOf("(") != -1
                            && (fract = Integer.parseInt(typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.indexOf(")")).trim())) > 0) {
                        // with fractional seconds
                        this.datetimePrecision = fract;
                        this.columnSize += fract + 1;
                    }
                    break;

                case DATETIME:
                case TIMESTAMP:
                    this.datetimePrecision = 0;
                    this.columnSize = 19;
                    if (typeInfo.indexOf("(") != -1
                            && (fract = Integer.parseInt(typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.indexOf(")")).trim())) > 0) {
                        // with fractional seconds
                        this.datetimePrecision = fract;
                        this.columnSize += fract + 1;
                    }
                    break;

                case BOOLEAN:
                case GEOMETRY:
                case NULL:
                case UNKNOWN:
                case YEAR:

                default:
            }

            // if not defined explicitly take the max precision
            if (this.columnSize == null) {
                // JDBC spec reserved only 'int' type for precision, thus we need to cut longer values
                this.columnSize = this.mysqlType.getPrecision() > Integer.MAX_VALUE ? Integer.MAX_VALUE : this.mysqlType.getPrecision().intValue();
            }

            switch (this.mysqlType) {
                case CHAR:
                case VARCHAR:
                case TINYTEXT:
                case MEDIUMTEXT:
                case LONGTEXT:
                case JSON:
                case TEXT:
                case TINYBLOB:
                case MEDIUMBLOB:
                case LONGBLOB:
                case BLOB:
                case BINARY:
                case VARBINARY:
                case BIT:
                    this.charOctetLength = this.columnSize;
                    break;
                default:
                    break;
            }

            // BUFFER_LENGTH
            this.bufferLength = maxBufferSize;

            // NUM_PREC_RADIX (is this right for char?)
            this.numPrecRadix = 10;

            // Nullable?
            if (nullabilityInfo != null) {
                if (nullabilityInfo.equals("YES")) {
                    this.nullability = columnNullable;
                    this.isNullable = "YES";

                } else if (nullabilityInfo.equals("UNKNOWN")) {
                    this.nullability = columnNullableUnknown;
                    this.isNullable = "";

                    // IS_NULLABLE
                } else {
                    this.nullability = columnNoNulls;
                    this.isNullable = "NO";
                }
            } else {
                this.nullability = columnNoNulls;
                this.isNullable = "NO";
            }
        }

    }

    /**
     * Helper class to provide means of comparing indexes by NON_UNIQUE, TYPE, INDEX_NAME, and ORDINAL_POSITION.
     */
    protected class IndexMetaDataKey implements Comparable<IndexMetaDataKey> {

        Boolean columnNonUnique;
        Short columnType;
        String columnIndexName;
        Short columnOrdinalPosition;

        IndexMetaDataKey(boolean columnNonUnique, short columnType, String columnIndexName, short columnOrdinalPosition) {
            this.columnNonUnique = columnNonUnique;
            this.columnType = columnType;
            this.columnIndexName = columnIndexName;
            this.columnOrdinalPosition = columnOrdinalPosition;
        }

        @Override
        public int compareTo(IndexMetaDataKey indexInfoKey) {
            int compareResult;

            if ((compareResult = this.columnNonUnique.compareTo(indexInfoKey.columnNonUnique)) != 0
                    || (compareResult = this.columnType.compareTo(indexInfoKey.columnType)) != 0
                    || (compareResult = this.columnIndexName.compareTo(indexInfoKey.columnIndexName)) != 0) {
                return compareResult;
            }
            return this.columnOrdinalPosition.compareTo(indexInfoKey.columnOrdinalPosition);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof IndexMetaDataKey)) {
                return false;
            }
            return compareTo((IndexMetaDataKey) obj) == 0;
        }

        @Override
        public int hashCode() {
            assert false : "hashCode not designed";
            return 0;
        }

    }

    /**
     * Helper class to provide means of comparing tables by TABLE_TYPE, TABLE_CAT, TABLE_SCHEM and TABLE_NAME.
     */
    protected class TableMetaDataKey implements Comparable<TableMetaDataKey> {

        String tableType;
        String tableCat;
        String tableSchem;
        String tableName;

        TableMetaDataKey(String tableType, String tableCat, String tableSchem, String tableName) {
            this.tableType = tableType == null ? "" : tableType;
            this.tableCat = tableCat == null ? "" : tableCat;
            this.tableSchem = tableSchem == null ? "" : tableSchem;
            this.tableName = tableName == null ? "" : tableName;
        }

        @Override
        public int compareTo(TableMetaDataKey tablesKey) {
            int compareResult;

            if ((compareResult = this.tableType.compareTo(tablesKey.tableType)) != 0 || (compareResult = this.tableCat.compareTo(tablesKey.tableCat)) != 0
                    || (compareResult = this.tableSchem.compareTo(tablesKey.tableSchem)) != 0) {
                return compareResult;
            }
            return this.tableName.compareTo(tablesKey.tableName);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof TableMetaDataKey)) {
                return false;
            }
            return compareTo((TableMetaDataKey) obj) == 0;
        }

        @Override
        public int hashCode() {
            assert false : "hashCode not designed";
            return 0;
        }

    }

    /**
     * Helper class to provide means of comparing fully qualified DB objects.
     */
    protected class TableDbObjectKey implements Comparable<TableDbObjectKey> {

        String dbName;
        String objectName;

        TableDbObjectKey(String dbName, String objectName) {
            this.dbName = dbName;
            this.objectName = objectName;
        }

        @Override
        public int compareTo(TableDbObjectKey tablesKey) {
            int compareResult;
            if ((compareResult = this.dbName.compareTo(tablesKey.dbName)) != 0 || (compareResult = this.objectName.compareTo(tablesKey.objectName)) != 0) {
                return compareResult;
            }
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof TableDbObjectKey)) {
                return false;
            }
            return compareTo((TableDbObjectKey) obj) == 0;
        }

        @Override
        public int hashCode() {
            assert false : "hashCode not designed";
            return 0;
        }

    }

    /**
     * Helper/wrapper class to provide means of sorting objects by using a sorting key.
     *
     * @param <K>
     *            key type
     * @param <V>
     *            value type
     */
    protected class ComparableWrapper<K extends Comparable<? super K>, V> implements Comparable<ComparableWrapper<K, V>> {

        K key;
        V value;

        public ComparableWrapper(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return this.key;
        }

        public V getValue() {
            return this.value;
        }

        @Override
        public int compareTo(ComparableWrapper<K, V> other) {
            return getKey().compareTo(other.getKey());
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }

            if (obj == this) {
                return true;
            }

            if (!(obj instanceof ComparableWrapper<?, ?>)) {
                return false;
            }

            Object otherKey = ((ComparableWrapper<?, ?>) obj).getKey();
            return this.key.equals(otherKey);
        }

        @Override
        public int hashCode() {
            assert false : "hashCode not designed";
            return 0;
        }

        @Override
        public String toString() {
            return "{KEY:" + this.key + "; VALUE:" + this.value + "}";
        }

    }

    /**
     * Enumeration for Table Types
     */
    protected enum TableType {

        LOCAL_TEMPORARY("LOCAL TEMPORARY"), SYSTEM_TABLE("SYSTEM TABLE"), SYSTEM_VIEW("SYSTEM VIEW"), TABLE("TABLE", new String[] { "BASE TABLE" }),
        VIEW("VIEW"), UNKNOWN("UNKNOWN");

        private String name;
        private byte[] nameAsBytes;
        private String[] synonyms;

        TableType(String tableTypeName) {
            this(tableTypeName, null);
        }

        TableType(String tableTypeName, String[] tableTypeSynonyms) {
            this.name = tableTypeName;
            this.nameAsBytes = tableTypeName.getBytes();
            this.synonyms = tableTypeSynonyms;
        }

        String getName() {
            return this.name;
        }

        byte[] asBytes() {
            return this.nameAsBytes;
        }

        boolean equalsTo(String tableTypeName) {
            return this.name.equalsIgnoreCase(tableTypeName);
        }

        static TableType getTableTypeEqualTo(String tableTypeName) {
            for (TableType tableType : TableType.values()) {
                if (tableType.equalsTo(tableTypeName)) {
                    return tableType;
                }
            }
            return UNKNOWN;
        }

        boolean compliesWith(String tableTypeName) {
            if (equalsTo(tableTypeName)) {
                return true;
            }
            if (this.synonyms != null) {
                for (String synonym : this.synonyms) {
                    if (synonym.equalsIgnoreCase(tableTypeName)) {
                        return true;
                    }
                }
            }
            return false;
        }

        static TableType getTableTypeCompliantWith(String tableTypeName) {
            for (TableType tableType : TableType.values()) {
                if (tableType.compliesWith(tableTypeName)) {
                    return tableType;
                }
            }
            return UNKNOWN;
        }

    }

    /**
     * Enumeration for Procedure Types
     */
    protected enum ProcedureType {
        PROCEDURE, FUNCTION;
    }

    protected static final int MAX_IDENTIFIER_LENGTH = 64;

    /** The table type for generic tables that support foreign keys. */
    private static final String SUPPORTS_FK = "SUPPORTS_FK";

    protected static final byte[] TABLE_AS_BYTES = "TABLE".getBytes();

    protected static final byte[] SYSTEM_TABLE_AS_BYTES = "SYSTEM TABLE".getBytes();

    protected static final byte[] VIEW_AS_BYTES = "VIEW".getBytes();

    // MySQL reserved words (all versions superset)
    private static final String[] MYSQL_KEYWORDS = new String[] { "ACCESSIBLE", "ADD", "ALL", "ALTER", "ANALYZE", "AND", "AS", "ASC", "ASENSITIVE", "BEFORE",
            "BETWEEN", "BIGINT", "BINARY", "BLOB", "BOTH", "BY", "CALL", "CASCADE", "CASE", "CHANGE", "CHAR", "CHARACTER", "CHECK", "COLLATE", "COLUMN",
            "CONDITION", "CONSTRAINT", "CONTINUE", "CONVERT", "CREATE", "CROSS", "CUBE", "CUME_DIST", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "CURRENT_USER", "CURSOR", "DATABASE", "DATABASES", "DAY_HOUR", "DAY_MICROSECOND", "DAY_MINUTE", "DAY_SECOND", "DEC", "DECIMAL", "DECLARE",
            "DEFAULT", "DELAYED", "DELETE", "DENSE_RANK", "DESC", "DESCRIBE", "DETERMINISTIC", "DISTINCT", "DISTINCTROW", "DIV", "DOUBLE", "DROP", "DUAL",
            "EACH", "ELSE", "ELSEIF", "EMPTY", "ENCLOSED", "ESCAPED", "EXCEPT", "EXISTS", "EXIT", "EXPLAIN", "FALSE", "FETCH", "FIRST_VALUE", "FLOAT", "FLOAT4",
            "FLOAT8", "FOR", "FORCE", "FOREIGN", "FROM", "FULLTEXT", "FUNCTION", "GENERATED", "GET", "GRANT", "GROUP", "GROUPING", "GROUPS", "HAVING",
            "HIGH_PRIORITY", "HOUR_MICROSECOND", "HOUR_MINUTE", "HOUR_SECOND", "IF", "IGNORE", "IN", "INDEX", "INFILE", "INNER", "INOUT", "INSENSITIVE",
            "INSERT", "INT", "INT1", "INT2", "INT3", "INT4", "INT8", "INTEGER", "INTERSECT", "INTERVAL", "INTO", "IO_AFTER_GTIDS", "IO_BEFORE_GTIDS", "IS",
            "ITERATE", "JOIN", "JSON_TABLE", "KEY", "KEYS", "KILL", "LAG", "LAST_VALUE", "LATERAL", "LEAD", "LEADING", "LEAVE", "LEFT", "LIKE", "LIMIT",
            "LINEAR", "LINES", "LOAD", "LOCALTIME", "LOCALTIMESTAMP", "LOCK", "LONG", "LONGBLOB", "LONGTEXT", "LOOP", "LOW_PRIORITY", "MANUAL", "MASTER_BIND",
            "MASTER_SSL_VERIFY_SERVER_CERT", "MATCH", "MAXVALUE", "MEDIUMBLOB", "MEDIUMINT", "MEDIUMTEXT", "MIDDLEINT", "MINUTE_MICROSECOND", "MINUTE_SECOND",
            "MOD", "MODIFIES", "NATURAL", "NOT", "NO_WRITE_TO_BINLOG", "NTH_VALUE", "NTILE", "NULL", "NUMERIC", "OF", "ON", "OPTIMIZE", "OPTIMIZER_COSTS",
            "OPTION", "OPTIONALLY", "OR", "ORDER", "OUT", "OUTER", "OUTFILE", "OVER", "PARALLEL", "PARTITION", "PERCENT_RANK", "PRECISION", "PRIMARY",
            "PROCEDURE", "PURGE", "QUALIFY", "RANGE", "RANK", "READ", "READS", "READ_WRITE", "REAL", "RECURSIVE", "REFERENCES", "REGEXP", "RELEASE", "RENAME",
            "REPEAT", "REPLACE", "REQUIRE", "RESIGNAL", "RESTRICT", "RETURN", "REVOKE", "RIGHT", "RLIKE", "ROW", "ROWS", "ROW_NUMBER", "SCHEMA", "SCHEMAS",
            "SECOND_MICROSECOND", "SELECT", "SENSITIVE", "SEPARATOR", "SET", "SHOW", "SIGNAL", "SMALLINT", "SPATIAL", "SPECIFIC", "SQL", "SQLEXCEPTION",
            "SQLSTATE", "SQLWARNING", "SQL_BIG_RESULT", "SQL_CALC_FOUND_ROWS", "SQL_SMALL_RESULT", "SSL", "STARTING", "STORED", "STRAIGHT_JOIN", "SYSTEM",
            "TABLE", "TABLESAMPLE", "TERMINATED", "THEN", "TINYBLOB", "TINYINT", "TINYTEXT", "TO", "TRAILING", "TRIGGER", "TRUE", "UNDO", "UNION", "UNIQUE",
            "UNLOCK", "UNSIGNED", "UPDATE", "USAGE", "USE", "USING", "UTC_DATE", "UTC_TIME", "UTC_TIMESTAMP", "VALUES", "VARBINARY", "VARCHAR", "VARCHARACTER",
            "VARYING", "VIRTUAL", "WHEN", "WHERE", "WHILE", "WINDOW", "WITH", "WRITE", "XOR", "YEAR_MONTH", "ZEROFILL" };

    // SQL:2003 reserved words from 'ISO/IEC 9075-2:2003 (E), 2003-07-25'
    /* package private */ static final List<String> SQL2003_KEYWORDS = Arrays.asList("ABS", "ALL", "ALLOCATE", "ALTER", "AND", "ANY", "ARE", "ARRAY", "AS",
            "ASENSITIVE", "ASYMMETRIC", "AT", "ATOMIC", "AUTHORIZATION", "AVG", "BEGIN", "BETWEEN", "BIGINT", "BINARY", "BLOB", "BOOLEAN", "BOTH", "BY", "CALL",
            "CALLED", "CARDINALITY", "CASCADED", "CASE", "CAST", "CEIL", "CEILING", "CHAR", "CHARACTER", "CHARACTER_LENGTH", "CHAR_LENGTH", "CHECK", "CLOB",
            "CLOSE", "COALESCE", "COLLATE", "COLLECT", "COLUMN", "COMMIT", "CONDITION", "CONNECT", "CONSTRAINT", "CONVERT", "CORR", "CORRESPONDING", "COUNT",
            "COVAR_POP", "COVAR_SAMP", "CREATE", "CROSS", "CUBE", "CUME_DIST", "CURRENT", "CURRENT_DATE", "CURRENT_DEFAULT_TRANSFORM_GROUP", "CURRENT_PATH",
            "CURRENT_ROLE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_TRANSFORM_GROUP_FOR_TYPE", "CURRENT_USER", "CURSOR", "CYCLE", "DATE", "DAY",
            "DEALLOCATE", "DEC", "DECIMAL", "DECLARE", "DEFAULT", "DELETE", "DENSE_RANK", "DEREF", "DESCRIBE", "DETERMINISTIC", "DISCONNECT", "DISTINCT",
            "DOUBLE", "DROP", "DYNAMIC", "EACH", "ELEMENT", "ELSE", "END", "END-EXEC", "ESCAPE", "EVERY", "EXCEPT", "EXEC", "EXECUTE", "EXISTS", "EXP",
            "EXTERNAL", "EXTRACT", "FALSE", "FETCH", "FILTER", "FLOAT", "FLOOR", "FOR", "FOREIGN", "FREE", "FROM", "FULL", "FUNCTION", "FUSION", "GET",
            "GLOBAL", "GRANT", "GROUP", "GROUPING", "HAVING", "HOLD", "HOUR", "IDENTITY", "IN", "INDICATOR", "INNER", "INOUT", "INSENSITIVE", "INSERT", "INT",
            "INTEGER", "INTERSECT", "INTERSECTION", "INTERVAL", "INTO", "IS", "JOIN", "LANGUAGE", "LARGE", "LATERAL", "LEADING", "LEFT", "LIKE", "LN", "LOCAL",
            "LOCALTIME", "LOCALTIMESTAMP", "LOWER", "MATCH", "MAX", "MEMBER", "MERGE", "METHOD", "MIN", "MINUTE", "MOD", "MODIFIES", "MODULE", "MONTH",
            "MULTISET", "NATIONAL", "NATURAL", "NCHAR", "NCLOB", "NEW", "NO", "NONE", "NORMALIZE", "NOT", "NULL", "NULLIF", "NUMERIC", "OCTET_LENGTH", "OF",
            "OLD", "ON", "ONLY", "OPEN", "OR", "ORDER", "OUT", "OUTER", "OVER", "OVERLAPS", "OVERLAY", "PARAMETER", "PARTITION", "PERCENTILE_CONT",
            "PERCENTILE_DISC", "PERCENT_RANK", "POSITION", "POWER", "PRECISION", "PREPARE", "PRIMARY", "PROCEDURE", "RANGE", "RANK", "READS", "REAL",
            "RECURSIVE", "REF", "REFERENCES", "REFERENCING", "REGR_AVGX", "REGR_AVGY", "REGR_COUNT", "REGR_INTERCEPT", "REGR_R2", "REGR_SLOPE", "REGR_SXX",
            "REGR_SXY", "REGR_SYY", "RELEASE", "RESULT", "RETURN", "RETURNS", "REVOKE", "RIGHT", "ROLLBACK", "ROLLUP", "ROW", "ROWS", "ROW_NUMBER", "SAVEPOINT",
            "SCOPE", "SCROLL", "SEARCH", "SECOND", "SELECT", "SENSITIVE", "SESSION_USER", "SET", "SIMILAR", "SMALLINT", "SOME", "SPECIFIC", "SPECIFICTYPE",
            "SQL", "SQLEXCEPTION", "SQLSTATE", "SQLWARNING", "SQRT", "START", "STATIC", "STDDEV_POP", "STDDEV_SAMP", "SUBMULTISET", "SUBSTRING", "SUM",
            "SYMMETRIC", "SYSTEM", "SYSTEM_USER", "TABLE", "TABLESAMPLE", "THEN", "TIME", "TIMESTAMP", "TIMEZONE_HOUR", "TIMEZONE_MINUTE", "TO", "TRAILING",
            "TRANSLATE", "TRANSLATION", "TREAT", "TRIGGER", "TRIM", "TRUE", "UESCAPE", "UNION", "UNIQUE", "UNKNOWN", "UNNEST", "UPDATE", "UPPER", "USER",
            "USING", "VALUE", "VALUES", "VARCHAR", "VARYING", "VAR_POP", "VAR_SAMP", "WHEN", "WHENEVER", "WHERE", "WIDTH_BUCKET", "WINDOW", "WITH", "WITHIN",
            "WITHOUT", "YEAR");

    private static volatile String mysqlKeywords = null;

    /** The connection to the database */
    protected JdbcConnection conn;

    protected NativeSession session;

    /** The 'current' database name being used */
    protected String database = null;

    /** What character to use when quoting identifiers */
    protected final String quotedId;

    protected boolean pedantic;
    protected boolean tinyInt1isBit;
    protected boolean transformedBitIsBoolean;
    protected boolean useHostsInPrivileges;
    protected boolean yearIsDateType;

    protected RuntimeProperty<DatabaseTerm> databaseTerm;
    protected RuntimeProperty<Boolean> nullDatabaseMeansCurrent;

    protected ResultSetFactory resultSetFactory;

    private String metadataEncoding;
    private int metadataCollationIndex;

    protected static DatabaseMetaData getInstance(JdbcConnection connToSet, String databaseToSet, boolean checkForInfoSchema, ResultSetFactory resultSetFactory)
            throws SQLException {
        if (checkForInfoSchema && connToSet.getPropertySet().getBooleanProperty(PropertyKey.useInformationSchema).getValue()) {
            return new DatabaseMetaDataUsingInfoSchema(connToSet, databaseToSet, resultSetFactory);
        }
        return new DatabaseMetaData(connToSet, databaseToSet, resultSetFactory);
    }

    /**
     * Creates a new DatabaseMetaData object.
     *
     * @param connToSet
     *            Connection object
     * @param databaseToSet
     *            database name
     * @param resultSetFactory
     *            {@link ResultSetFactory}
     */
    protected DatabaseMetaData(JdbcConnection connToSet, String databaseToSet, ResultSetFactory resultSetFactory) {
        this.conn = connToSet;
        this.session = (NativeSession) connToSet.getSession();
        this.database = databaseToSet;
        this.resultSetFactory = resultSetFactory;
        this.exceptionInterceptor = this.conn.getExceptionInterceptor();
        this.databaseTerm = this.conn.getPropertySet().<DatabaseTerm>getEnumProperty(PropertyKey.databaseTerm);
        this.nullDatabaseMeansCurrent = this.conn.getPropertySet().getBooleanProperty(PropertyKey.nullDatabaseMeansCurrent);
        this.pedantic = this.conn.getPropertySet().getBooleanProperty(PropertyKey.pedantic).getValue();
        this.tinyInt1isBit = this.conn.getPropertySet().getBooleanProperty(PropertyKey.tinyInt1isBit).getValue();
        this.transformedBitIsBoolean = this.conn.getPropertySet().getBooleanProperty(PropertyKey.transformedBitIsBoolean).getValue();
        this.useHostsInPrivileges = this.conn.getPropertySet().getBooleanProperty(PropertyKey.useHostsInPrivileges).getValue();
        this.yearIsDateType = this.conn.getPropertySet().getBooleanProperty(PropertyKey.yearIsDateType).getValue();
        this.quotedId = this.session.getIdentifierQuoteString();
    }

    @Override
    public boolean allProceduresAreCallable() throws SQLException {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() throws SQLException {
        return false;
    }

    protected void convertToJdbcFunctionList(ResultSet proceduresRs, List<ComparableWrapper<String, Row>> procedureRows, Field[] fields) throws SQLException {
        while (proceduresRs.next()) {
            String procDb = proceduresRs.getString("db");
            String functionName = proceduresRs.getString("name");

            byte[][] rowData = null;

            if (fields != null && fields.length == 9) {
                rowData = new byte[9][];
                rowData[0] = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? s2b("def") : s2b(procDb);   // PROCEDURE_CAT
                rowData[1] = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? s2b(procDb) : null;         // PROCEDURE_SCHEM
                rowData[2] = s2b(functionName);                             // PROCEDURE_NAME
                rowData[3] = null;                                          // reserved1
                rowData[4] = null;                                          // reserved2
                rowData[5] = null;                                          // reserved3
                rowData[6] = s2b(proceduresRs.getString("comment"));        // REMARKS
                rowData[7] = s2b(Integer.toString(procedureReturnsResult)); // PROCEDURE_TYPE
                rowData[8] = s2b(functionName);
            } else {
                rowData = new byte[6][];
                rowData[0] = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? s2b("def") : s2b(procDb);   // PROCEDURE_CAT
                rowData[1] = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? s2b(procDb) : null;         // PROCEDURE_SCHEM
                rowData[2] = s2b(functionName);                      // FUNCTION_NAME
                rowData[3] = s2b(proceduresRs.getString("comment")); // REMARKS
                rowData[4] = s2b(Integer.toString(functionNoTable)); // FUNCTION_TYPE
                rowData[5] = s2b(functionName);                      // SPECFIC NAME
            }

            procedureRows.add(new ComparableWrapper<>(StringUtils.getFullyQualifiedName(procDb, functionName, this.quotedId, this.pedantic),
                    new ByteArrayRow(rowData, getExceptionInterceptor())));
        }
    }

    protected void convertToJdbcProcedureList(boolean fromSelect, ResultSet proceduresRs, List<ComparableWrapper<String, Row>> procedureRows)
            throws SQLException {
        while (proceduresRs.next()) {
            String procDb = proceduresRs.getString("db");
            String procedureName = proceduresRs.getString("name");
            byte[][] rowData = new byte[9][];
            rowData[0] = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? s2b("def") : s2b(procDb);
            rowData[1] = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? s2b(procDb) : null;
            rowData[2] = s2b(procedureName);
            rowData[3] = null;
            rowData[4] = null;
            rowData[5] = null;
            rowData[6] = s2b(proceduresRs.getString("comment"));
            boolean isFunction = fromSelect ? "FUNCTION".equalsIgnoreCase(proceduresRs.getString("type")) : false;
            rowData[7] = s2b(isFunction ? Integer.toString(procedureReturnsResult) : Integer.toString(procedureNoResult));
            rowData[8] = s2b(procedureName);

            procedureRows.add(new ComparableWrapper<>(StringUtils.getFullyQualifiedName(procDb, procedureName, this.quotedId, this.pedantic),
                    new ByteArrayRow(rowData, getExceptionInterceptor())));
        }
    }

    private Row convertTypeDescriptorToProcedureRow(byte[] procNameAsBytes, byte[] procCatAsBytes, String paramName, boolean isOutParam, boolean isInParam,
            boolean isReturnParam, TypeDescriptor typeDesc, boolean forGetFunctionColumns, int ordinal) throws SQLException {
        byte[][] row = forGetFunctionColumns ? new byte[17][] : new byte[20][];
        row[0] = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? s2b("def") : procCatAsBytes;                                // PROCEDURE_CAT
        row[1] = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? procCatAsBytes : null;                                      // PROCEDURE_SCHEM
        row[2] = procNameAsBytes;                                                                                   // PROCEDURE/NAME
        row[3] = s2b(paramName);                                                                                    // COLUMN_NAME
        row[4] = s2b(String.valueOf(getColumnType(isOutParam, isInParam, isReturnParam, forGetFunctionColumns)));   // COLUMN_TYPE
        row[5] = Short.toString(typeDesc.mysqlType == MysqlType.YEAR && !DatabaseMetaData.this.yearIsDateType ?     //
                Types.SMALLINT : (short) typeDesc.mysqlType.getJdbcType()).getBytes();                              // DATA_TYPE (jdbc)
        row[6] = s2b(typeDesc.mysqlType.getName());                                                                 // TYPE_NAME
        row[7] = typeDesc.datetimePrecision == null ? s2b(typeDesc.columnSize.toString()) : s2b(typeDesc.datetimePrecision.toString());            // PRECISION
        row[8] = typeDesc.columnSize == null ? null : s2b(typeDesc.columnSize.toString());                          // LENGTH
        row[9] = typeDesc.decimalDigits == null ? null : s2b(typeDesc.decimalDigits.toString());                    // SCALE
        row[10] = s2b(Integer.toString(typeDesc.numPrecRadix));                                                     // RADIX
        // Map 'column****' to 'procedure****'
        switch (typeDesc.nullability) {
            case columnNoNulls:
                row[11] = s2b(String.valueOf(procedureNoNulls));                                                    // NULLABLE
                break;

            case columnNullable:
                row[11] = s2b(String.valueOf(procedureNullable));                                                   // NULLABLE
                break;

            case columnNullableUnknown:
                row[11] = s2b(String.valueOf(procedureNullableUnknown));                                            // NULLABLE
                break;

            default:
                throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.1"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                        getExceptionInterceptor());
        }

        row[12] = null;

        if (forGetFunctionColumns) {
            row[13] = typeDesc.charOctetLength == null ? null : s2b(typeDesc.charOctetLength.toString());           // CHAR_OCTET_LENGTH
            row[14] = s2b(String.valueOf(ordinal));                                                                 // ORDINAL_POSITION
            row[15] = s2b(typeDesc.isNullable);                                                                     // IS_NULLABLE
            row[16] = procNameAsBytes;                                                                              // SPECIFIC_NAME

        } else {
            row[13] = null;                                                                                         // COLUMN_DEF
            row[14] = null;                                                                                         // SQL_DATA_TYPE (future use)
            row[15] = null;                                                                                         // SQL_DATETIME_SUB (future use)
            row[16] = typeDesc.charOctetLength == null ? null : s2b(typeDesc.charOctetLength.toString());           // CHAR_OCTET_LENGTH
            row[17] = s2b(String.valueOf(ordinal));                                                                 // ORDINAL_POSITION
            row[18] = s2b(typeDesc.isNullable);                                                                     // IS_NULLABLE
            row[19] = procNameAsBytes;                                                                              // SPECIFIC_NAME
        }

        return new ByteArrayRow(row, getExceptionInterceptor());
    }

    /**
     * Determines the COLUMN_TYPE information based on parameter type (IN, OUT or INOUT) or function return parameter.
     *
     * @param isOutParam
     *            Indicates whether it's an output parameter.
     * @param isInParam
     *            Indicates whether it's an input parameter.
     * @param isReturnParam
     *            Indicates whether it's a function return parameter.
     * @param forGetFunctionColumns
     *            Indicates whether the column belong to a function. This argument is required for JDBC4, in which case
     *            this method must be overridden to provide the correct functionality.
     *
     * @return The corresponding COLUMN_TYPE as in java.sql.DatabaseMetaData.getProcedureColumns API.
     */
    protected int getColumnType(boolean isOutParam, boolean isInParam, boolean isReturnParam, boolean forGetFunctionColumns) {
        return getProcedureOrFunctionColumnType(isOutParam, isInParam, isReturnParam, forGetFunctionColumns);
    }

    /**
     * Determines the COLUMN_TYPE information based on parameter type (IN, OUT or INOUT) or function return parameter.
     *
     * @param isOutParam
     *            Indicates whether it's an output parameter.
     * @param isInParam
     *            Indicates whether it's an input parameter.
     * @param isReturnParam
     *            Indicates whether it's a function return parameter.
     * @param forGetFunctionColumns
     *            Indicates whether the column belong to a function.
     *
     * @return The corresponding COLUMN_TYPE as in java.sql.DatabaseMetaData.getProcedureColumns API.
     */
    protected static int getProcedureOrFunctionColumnType(boolean isOutParam, boolean isInParam, boolean isReturnParam, boolean forGetFunctionColumns) {
        if (isInParam && isOutParam) {
            return forGetFunctionColumns ? functionColumnInOut : procedureColumnInOut;
        } else if (isInParam) {
            return forGetFunctionColumns ? functionColumnIn : procedureColumnIn;
        } else if (isOutParam) {
            return forGetFunctionColumns ? functionColumnOut : procedureColumnOut;
        } else if (isReturnParam) {
            return forGetFunctionColumns ? functionReturn : procedureColumnReturn;
        } else {
            return forGetFunctionColumns ? functionColumnUnknown : procedureColumnUnknown;
        }
    }

    private ExceptionInterceptor exceptionInterceptor;

    protected ExceptionInterceptor getExceptionInterceptor() {
        return this.exceptionInterceptor;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
        return true;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
        return true;
    }

    /**
     * Extracts foreign key info for one table.
     *
     * @param rows
     *            the list of rows to add to
     * @param rs
     *            the result set from 'SHOW CREATE TABLE'
     * @param dbName
     *            the database name
     * @return the list of rows with new rows added
     * @throws SQLException
     *             if a database access error occurs
     */
    private List<Row> extractForeignKeyForTable(ArrayList<Row> rows, ResultSet rs, String dbName) throws SQLException {
        byte[][] row = new byte[3][];
        row[0] = rs.getBytes(1);
        row[1] = s2b(SUPPORTS_FK);

        String createTableString = rs.getString(2);
        StringTokenizer lineTokenizer = new StringTokenizer(createTableString, "\n");
        StringBuilder comment = new StringBuilder("Key info; ");
        boolean firstTime = true;

        while (lineTokenizer.hasMoreTokens()) {
            String line = lineTokenizer.nextToken().trim();
            String constraintName = null;
            if (StringUtils.startsWithIgnoreCase(line, "CONSTRAINT")) {
                boolean usingBackTicks = true;
                int beginPos = StringUtils.indexOfQuoteDoubleAware(line, this.quotedId, 0);
                if (beginPos == -1) {
                    beginPos = line.indexOf("\"");
                    usingBackTicks = false;
                }
                if (beginPos != -1) {
                    int endPos = -1;
                    if (usingBackTicks) {
                        endPos = StringUtils.indexOfQuoteDoubleAware(line, this.quotedId, beginPos + 1);
                    } else {
                        endPos = StringUtils.indexOfQuoteDoubleAware(line, "\"", beginPos + 1);
                    }
                    if (endPos != -1) {
                        constraintName = line.substring(beginPos + 1, endPos);
                        line = line.substring(endPos + 1, line.length()).trim();
                    }
                }
            }

            if (line.startsWith("FOREIGN KEY")) {
                if (line.endsWith(",")) {
                    line = line.substring(0, line.length() - 1);
                }
                int indexOfFK = line.indexOf("FOREIGN KEY");
                String localColumnName = null;
                String referencedDbName = StringUtils.quoteIdentifier(dbName, this.quotedId, true);
                String referencedTableName = null;
                String referencedColumnName = null;
                if (indexOfFK != -1) {
                    int afterFk = indexOfFK + "FOREIGN KEY".length();
                    int indexOfRef = StringUtils.indexOfIgnoreCase(afterFk, line, "REFERENCES", this.quotedId, this.quotedId,
                            SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
                    if (indexOfRef != -1) {
                        int indexOfParenOpen = line.indexOf('(', afterFk);
                        int indexOfParenClose = StringUtils.indexOfIgnoreCase(indexOfParenOpen, line, ")", this.quotedId, this.quotedId,
                                SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
                        localColumnName = line.substring(indexOfParenOpen + 1, indexOfParenClose);
                        int afterRef = indexOfRef + "REFERENCES".length();
                        int referencedColumnBegin = StringUtils.indexOfIgnoreCase(afterRef, line, "(", this.quotedId, this.quotedId,
                                SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
                        if (referencedColumnBegin != -1) {
                            referencedTableName = line.substring(afterRef, referencedColumnBegin).trim();
                            int referencedColumnEnd = StringUtils.indexOfIgnoreCase(referencedColumnBegin + 1, line, ")", this.quotedId, this.quotedId,
                                    SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
                            if (referencedColumnEnd != -1) {
                                referencedColumnName = line.substring(referencedColumnBegin + 1, referencedColumnEnd);
                            }
                            int indexOfDbSep = StringUtils.indexOfIgnoreCase(0, referencedTableName, ".", this.quotedId, this.quotedId,
                                    SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
                            if (indexOfDbSep != -1) {
                                referencedDbName = referencedTableName.substring(0, indexOfDbSep);
                                referencedTableName = referencedTableName.substring(indexOfDbSep + 1);
                            }
                        }
                    }
                }

                if (firstTime) {
                    firstTime = false;
                } else {
                    comment.append("; ");
                }
                if (constraintName != null) {
                    comment.append(constraintName);
                } else {
                    comment.append("not_available");
                }
                comment.append("(");
                comment.append(localColumnName);
                comment.append(") REFER ");
                comment.append(referencedDbName);
                comment.append("/");
                comment.append(referencedTableName);
                comment.append("(");
                comment.append(referencedColumnName);
                comment.append(")");
                int lastParenIndex = line.lastIndexOf(")");
                if (lastParenIndex != line.length() - 1) {
                    String cascadeOptions = line.substring(lastParenIndex + 1);
                    comment.append(cascadeOptions);
                }
            }
        }

        row[2] = s2b(comment.toString());
        rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
        return rows;
    }

    /**
     * Creates a result set similar enough to 'SHOW TABLE STATUS' to allow the same code to work on extracting the foreign key data
     *
     * @param dbName
     *            the database name to extract foreign key info for
     * @param tableName
     *            the table to extract foreign key info for
     * @return A result set that has the structure of 'show table status'
     * @throws SQLException
     *             if a database access error occurs.
     */
    public ResultSet extractForeignKeyFromCreateTable(String dbName, String tableName) throws SQLException {
        ArrayList<String> tableList = new ArrayList<>();
        ResultSet rs = null;
        Statement stmt = null;

        if (tableName != null) {
            tableList.add(tableName);
        } else {
            try {
                String quotedDbName = dbName;
                if (!this.pedantic) {
                    quotedDbName = StringUtils.quoteIdentifier(dbName, this.quotedId, true); // Quote database name before calling #getTables().
                }
                rs = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? getTables(null, quotedDbName, null, new String[] { "TABLE" })
                        : getTables(quotedDbName, null, null, new String[] { "TABLE" });
                while (rs.next()) {
                    tableList.add(rs.getString("TABLE_NAME"));
                }
            } finally {
                if (rs != null) {
                    rs.close();
                    rs = null;
                }
            }
        }

        ArrayList<Row> rows = new ArrayList<>();
        Field[] fields = new Field[3];
        fields[0] = new Field("", "Name", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, Integer.MAX_VALUE);
        fields[1] = new Field("", "Type", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[2] = new Field("", "Comment", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, Integer.MAX_VALUE);

        stmt = this.conn.getMetadataSafeStatement();
        try {
            for (String tableToExtract : tableList) {
                StringBuilder query = new StringBuilder("SHOW CREATE TABLE ");
                query.append(StringUtils.getFullyQualifiedName(dbName, tableToExtract, this.quotedId, true));
                try {
                    rs = stmt.executeQuery(query.toString());
                } catch (SQLException e) {
                    String sqlState = e.getSQLState(); // Ignore exception if SQLState is 42S02 or 42000 - table/database doesn't exist.
                    int errorCode = e.getErrorCode(); // Ignore exception if ErrorCode is 1146, 1109, or 1149 - table/database doesn't exist.

                    if (!("42S02".equals(sqlState) && (errorCode == MysqlErrorNumbers.ER_NO_SUCH_TABLE || errorCode == MysqlErrorNumbers.ER_UNKNOWN_TABLE)
                            || "42000".equals(sqlState) && errorCode == MysqlErrorNumbers.ER_BAD_DB_ERROR)) {
                        throw e;
                    }
                    continue;
                }

                while (rs != null && rs.next()) {
                    extractForeignKeyForTable(rows, rs, dbName);
                }
            }
        } finally {
            if (rs != null) {
                rs.close();
                rs = null;
            }
            if (stmt != null) {
                stmt.close();
                stmt = null;
            }
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) throws SQLException {
        Field[] fields = new Field[21];
        fields[0] = new Field("", "TYPE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[1] = new Field("", "TYPE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[2] = new Field("", "TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[3] = new Field("", "ATTR_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[4] = new Field("", "DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 32);
        fields[5] = new Field("", "ATTR_TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[6] = new Field("", "ATTR_SIZE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 32);
        fields[7] = new Field("", "DECIMAL_DIGITS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 32);
        fields[8] = new Field("", "NUM_PREC_RADIX", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 32);
        fields[9] = new Field("", "NULLABLE ", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 32);
        fields[10] = new Field("", "REMARKS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[11] = new Field("", "ATTR_DEF", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[12] = new Field("", "SQL_DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 32);
        fields[13] = new Field("", "SQL_DATETIME_SUB", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 32);
        fields[14] = new Field("", "CHAR_OCTET_LENGTH", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 32);
        fields[15] = new Field("", "ORDINAL_POSITION", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 32);
        fields[16] = new Field("", "IS_NULLABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[17] = new Field("", "SCOPE_CATALOG", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[18] = new Field("", "SCOPE_SCHEMA", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[19] = new Field("", "SCOPE_TABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[20] = new Field("", "SOURCE_DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 32);

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, final String table, int scope, boolean nullable) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFilter = getDatabase(catalog, schema);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);

        Field[] fields = new Field[8];
        fields[0] = new Field("", "SCOPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 5);
        fields[1] = new Field("", "COLUMN_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[2] = new Field("", "DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 32);
        fields[3] = new Field("", "TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[4] = new Field("", "COLUMN_SIZE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[5] = new Field("", "BUFFER_LENGTH", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[6] = new Field("", "DECIMAL_DIGITS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 10);
        fields[7] = new Field("", "PSEUDO_COLUMN", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 5);

        final ArrayList<Row> rows = new ArrayList<>();
        final Statement stmt = this.conn.getMetadataSafeStatement();

        try {
            new IterateBlock<String>(getDatabaseIterator(dbFilter)) {

                @Override
                void forEach(String db) throws SQLException {
                    ResultSet rs = null;
                    try {
                        StringBuilder query = new StringBuilder("SHOW COLUMNS FROM ");
                        query.append(StringUtils.quoteIdentifier(tableFilter, DatabaseMetaData.this.quotedId, true));
                        query.append(" FROM ");
                        query.append(StringUtils.quoteIdentifier(db, DatabaseMetaData.this.quotedId, true));

                        try {
                            rs = stmt.executeQuery(query.toString());
                        } catch (SQLException e) {
                            String sqlState = e.getSQLState(); // Ignore exception if SQLState is 42S02 or 42000 - table/database doesn't exist.
                            int errorCode = e.getErrorCode(); // Ignore exception if ErrorCode is 1146, 1109, or 1149 - table/database doesn't exist.

                            if (!("42S02".equals(sqlState)
                                    && (errorCode == MysqlErrorNumbers.ER_NO_SUCH_TABLE || errorCode == MysqlErrorNumbers.ER_UNKNOWN_TABLE)
                                    || "42000".equals(sqlState) && errorCode == MysqlErrorNumbers.ER_BAD_DB_ERROR)) {
                                throw e;
                            }
                        }

                        while (rs != null && rs.next()) {
                            String keyType = rs.getString("Key");

                            if (keyType != null) {
                                if (StringUtils.startsWithIgnoreCase(keyType, "PRI")) {
                                    byte[][] row = new byte[8][];
                                    row[0] = Integer.toString(bestRowSession).getBytes();
                                    row[1] = rs.getBytes("Field");

                                    String type = rs.getString("Type");
                                    int size = stmt.getMaxFieldSize();
                                    int decimals = 0;
                                    boolean hasLength = false;

                                    // Parse the Type column from MySQL.
                                    if (type.indexOf("enum") != -1) {
                                        String temp = type.substring(type.indexOf("("), type.indexOf(")"));
                                        java.util.StringTokenizer tokenizer = new java.util.StringTokenizer(temp, ",");
                                        int maxLength = 0;
                                        while (tokenizer.hasMoreTokens()) {
                                            maxLength = Math.max(maxLength, tokenizer.nextToken().length() - 2);
                                        }
                                        size = maxLength;
                                        decimals = 0;
                                        type = "enum";
                                    } else if (type.indexOf("(") != -1) {
                                        hasLength = true;
                                        if (type.indexOf(",") != -1) {
                                            size = Integer.parseInt(type.substring(type.indexOf("(") + 1, type.indexOf(",")));
                                            decimals = Integer.parseInt(type.substring(type.indexOf(",") + 1, type.indexOf(")")));
                                        } else {
                                            size = Integer.parseInt(type.substring(type.indexOf("(") + 1, type.indexOf(")")));
                                        }
                                        type = type.substring(0, type.indexOf("("));
                                    }

                                    MysqlType ft = MysqlType.getByName(type.toUpperCase());
                                    row[2] = s2b(
                                            String.valueOf(ft == MysqlType.YEAR && !DatabaseMetaData.this.yearIsDateType ? Types.SMALLINT : ft.getJdbcType()));
                                    row[3] = s2b(type);
                                    row[4] = hasLength ? Integer.toString(size + decimals).getBytes() : Long.toString(ft.getPrecision()).getBytes();
                                    row[5] = Integer.toString(maxBufferSize).getBytes();
                                    row[6] = Integer.toString(decimals).getBytes();
                                    row[7] = Integer.toString(bestRowNotPseudo).getBytes();

                                    rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                                }
                            }
                        }
                    } catch (SQLException sqlEx) {
                        if (!MysqlErrorNumbers.SQLSTATE_MYSQL_BASE_TABLE_OR_VIEW_NOT_FOUND.equals(sqlEx.getSQLState())) {
                            throw sqlEx;
                        }
                    } finally {
                        if (rs != null) {
                            try {
                                rs.close();
                            } catch (Exception ex) {
                            }
                            rs = null;
                        }
                    }
                }

            }.doForAll();
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    /*
     * Extract parameter details for Procedures and Functions by parsing the DDL query obtained from SHOW CREATE [PROCEDURE|FUNCTION] ... statements.
     * The result rows returned follow the required structure for getProcedureColumns() and getFunctionColumns() methods.
     *
     * Internal use only.
     */
    private void getProcedureOrFunctionParameterTypes(String dbName, String objectName, ProcedureType procType, String parameterNamePattern,
            List<Row> resultRows, boolean forGetFunctionColumns) throws SQLException {
        Statement paramRetrievalStmt = null;
        ResultSet paramRetrievalRs = null;

        String parameterDef = null;

        byte[] procNameAsBytes = null;
        byte[] procCatAsBytes = null;

        boolean isProcedureInAnsiMode = false;
        String storageDefnDelims = null;
        String storageDefnClosures = null;

        try {
            paramRetrievalStmt = this.conn.getMetadataSafeStatement();
            if (paramRetrievalStmt.getMaxRows() != 0) {
                paramRetrievalStmt.setMaxRows(0);
            }

            procCatAsBytes = StringUtils.getBytes(dbName, "UTF-8");
            procNameAsBytes = StringUtils.getBytes(objectName, "UTF-8");

            String fieldName = null;
            StringBuilder query = new StringBuilder();
            if (procType == PROCEDURE) {
                fieldName = "Create Procedure";
                query.append("SHOW CREATE PROCEDURE ");
            } else {
                fieldName = "Create Function";
                query.append("SHOW CREATE FUNCTION ");
            }
            query.append(StringUtils.quoteIdentifier(dbName, this.quotedId, true));
            query.append('.');
            query.append(StringUtils.quoteIdentifier(objectName, this.quotedId, true));

            paramRetrievalRs = paramRetrievalStmt.executeQuery(query.toString());

            if (paramRetrievalRs.next()) {
                String procedureDef = paramRetrievalRs.getString(fieldName);

                if (!this.conn.getPropertySet().getBooleanProperty(PropertyKey.noAccessToProcedureBodies).getValue()
                        && (procedureDef == null || procedureDef.length() == 0)) {
                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.4"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                            getExceptionInterceptor());
                }

                try {
                    String sqlMode = paramRetrievalRs.getString("sql_mode");
                    if (StringUtils.indexOfIgnoreCase(sqlMode, "ANSI") != -1) {
                        isProcedureInAnsiMode = true;
                    }
                } catch (SQLException sqlEx) {
                    // doesn't exist
                }

                String identifierMarkers = isProcedureInAnsiMode ? "`\"" : "`";
                String identifierAndStringMarkers = "'" + identifierMarkers;
                storageDefnDelims = "(" + identifierMarkers;
                storageDefnClosures = ")" + identifierMarkers;

                if (procedureDef != null && procedureDef.length() != 0) {
                    // sanitize/normalize by stripping out comments
                    procedureDef = StringUtils.stripCommentsAndHints(procedureDef, identifierAndStringMarkers, identifierAndStringMarkers,
                            !this.session.getServerSession().isNoBackslashEscapesSet());
                    int openParenIndex = StringUtils.indexOfIgnoreCase(0, procedureDef, "(", this.quotedId, this.quotedId,
                            this.session.getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__FULL);
                    int endOfParamDeclarationIndex = 0;
                    endOfParamDeclarationIndex = endPositionOfParameterDeclaration(openParenIndex, procedureDef, this.quotedId);

                    if (procType == FUNCTION) {
                        // Grab the return column since it needs to go first in the output result set.
                        int returnsIndex = StringUtils.indexOfIgnoreCase(0, procedureDef, " RETURNS ", this.quotedId, this.quotedId,
                                this.session.getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__FULL);
                        int endReturnsDef = findEndOfReturnsClause(procedureDef, returnsIndex);

                        // Trim off whitespace after "RETURNS"
                        int declarationStart = returnsIndex + "RETURNS ".length();
                        while (declarationStart < procedureDef.length()) {
                            if (Character.isWhitespace(procedureDef.charAt(declarationStart))) {
                                declarationStart++;
                            } else {
                                break;
                            }
                        }

                        String returnsDefn = procedureDef.substring(declarationStart, endReturnsDef).trim();
                        TypeDescriptor returnDescriptor = new TypeDescriptor(returnsDefn, "YES");

                        resultRows.add(convertTypeDescriptorToProcedureRow(procNameAsBytes, procCatAsBytes, "", false, false, true, returnDescriptor,
                                forGetFunctionColumns, 0));
                    }

                    if (openParenIndex == -1 || endOfParamDeclarationIndex == -1) {
                        throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.5"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                getExceptionInterceptor());
                    }

                    parameterDef = procedureDef.substring(openParenIndex + 1, endOfParamDeclarationIndex);
                }

            }
        } finally {
            SQLException sqlExRethrow = null;
            if (paramRetrievalRs != null) {
                try {
                    paramRetrievalRs.close();
                } catch (SQLException sqlEx) {
                    sqlExRethrow = sqlEx;
                }
                paramRetrievalRs = null;
            }
            if (paramRetrievalStmt != null) {
                try {
                    paramRetrievalStmt.close();
                } catch (SQLException sqlEx) {
                    sqlExRethrow = sqlEx;
                }
                paramRetrievalStmt = null;
            }
            if (sqlExRethrow != null) {
                throw sqlExRethrow;
            }
        }

        if (parameterDef != null) {
            int ordinal = 1;
            List<String> parseList = StringUtils.split(parameterDef, ",", storageDefnDelims, storageDefnClosures, true);
            int parseListLen = parseList.size();

            for (int i = 0; i < parseListLen; i++) {
                String declaration = parseList.get(i);

                if (declaration.trim().length() == 0) {
                    break; // no parameters actually declared, but whitespace spans lines
                }

                // Bug#52167, tokenizer will break if declaration contains special characters like \n
                declaration = declaration.replaceAll("[\\t\\n\\x0B\\f\\r]", " ");
                StringTokenizer declarationTok = new StringTokenizer(declaration, " \t");

                String paramName = null;
                boolean isOutParam = false;
                boolean isInParam = false;

                if (declarationTok.hasMoreTokens()) {
                    String possibleParamName = declarationTok.nextToken();

                    if (possibleParamName.equalsIgnoreCase("OUT")) {
                        isOutParam = true;

                        if (declarationTok.hasMoreTokens()) {
                            paramName = declarationTok.nextToken();
                        } else {
                            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.6"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                    getExceptionInterceptor());
                        }
                    } else if (possibleParamName.equalsIgnoreCase("INOUT")) {
                        isOutParam = true;
                        isInParam = true;

                        if (declarationTok.hasMoreTokens()) {
                            paramName = declarationTok.nextToken();
                        } else {
                            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.6"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                    getExceptionInterceptor());
                        }
                    } else if (possibleParamName.equalsIgnoreCase("IN")) {
                        isOutParam = false;
                        isInParam = true;

                        if (declarationTok.hasMoreTokens()) {
                            paramName = declarationTok.nextToken();
                        } else {
                            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.6"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                    getExceptionInterceptor());
                        }
                    } else {
                        isOutParam = false;
                        isInParam = true;

                        paramName = possibleParamName;
                    }

                    TypeDescriptor typeDesc = null;

                    if (declarationTok.hasMoreTokens()) {
                        StringBuilder typeInfo = new StringBuilder(declarationTok.nextToken());

                        while (declarationTok.hasMoreTokens()) {
                            typeInfo.append(" ");
                            typeInfo.append(declarationTok.nextToken());
                        }

                        typeDesc = new TypeDescriptor(typeInfo.toString(), "YES");
                    } else {
                        throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.7"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                getExceptionInterceptor());
                    }

                    if (paramName.startsWith("`") && paramName.endsWith("`")) {
                        paramName = StringUtils.unQuoteIdentifier(paramName, "`");
                    } else if (isProcedureInAnsiMode && paramName.startsWith("\"") && paramName.endsWith("\"")) {
                        paramName = StringUtils.unQuoteIdentifier(paramName, "\"");
                    }

                    final String paramNameFilter = this.pedantic ? parameterNamePattern : StringUtils.unQuoteIdentifier(parameterNamePattern, this.quotedId);
                    if (paramNameFilter == null || StringUtils.wildCompareIgnoreCase(paramName, paramNameFilter)) {
                        Row row = convertTypeDescriptorToProcedureRow(procNameAsBytes, procCatAsBytes, paramName, isOutParam, isInParam, false, typeDesc,
                                forGetFunctionColumns, ordinal++);

                        resultRows.add(row);
                    }
                } else {
                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.8"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                            getExceptionInterceptor());
                }
            }
        } else {
            // Is this an error? JDBC spec doesn't make it clear if stored procedure doesn't exist, is it an error....
        }
    }

    /**
     * Finds the end of the parameter declaration from the output of "SHOW
     * CREATE PROCEDURE".
     *
     * @param beginIndex
     *            should be the index of the procedure body that contains the
     *            first "(".
     * @param procedureDef
     *            the procedure body
     * @param quoteChar
     *            the identifier quote character in use
     * @return the ending index of the parameter declaration, not including the
     *         closing ")"
     * @throws SQLException
     *             if a parse error occurs.
     */
    private int endPositionOfParameterDeclaration(int beginIndex, String procedureDef, String quoteChar) throws SQLException {
        int currentPos = beginIndex + 1;
        int parenDepth = 1; // counting the first openParen

        while (parenDepth > 0 && currentPos < procedureDef.length()) {
            int closedParenIndex = StringUtils.indexOfIgnoreCase(currentPos, procedureDef, ")", quoteChar, quoteChar,
                    this.session.getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__BSE_MRK_COM_MYM_HNT_WS);

            if (closedParenIndex != -1) {
                int nextOpenParenIndex = StringUtils.indexOfIgnoreCase(currentPos, procedureDef, "(", quoteChar, quoteChar,
                        this.session.getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__BSE_MRK_COM_MYM_HNT_WS);

                if (nextOpenParenIndex != -1 && nextOpenParenIndex < closedParenIndex) {
                    parenDepth++;
                    currentPos = closedParenIndex + 1; // set after closed paren that increases depth
                } else {
                    parenDepth--;
                    currentPos = closedParenIndex; // start search from same position
                }
            } else {
                // we should always get closed paren of some sort
                throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.5"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                        getExceptionInterceptor());
            }
        }

        return currentPos;
    }

    /**
     * Finds the end of the RETURNS clause for SQL Functions by using any of the
     * keywords allowed after the RETURNS clause, or a label.
     *
     * @param procedureDefn
     *            the function body containing the definition of the function
     * @param positionOfReturnKeyword
     *            the position of "RETURNS" in the definition
     * @return the end of the returns clause
     * @throws SQLException
     *             if a parse error occurs
     */
    private int findEndOfReturnsClause(String procedureDefn, int positionOfReturnKeyword) throws SQLException {
        /*
         * characteristic: LANGUAGE SQL | [NOT] DETERMINISTIC | { CONTAINS SQL |
         * NO SQL | READS SQL DATA | MODIFIES SQL DATA } | SQL SECURITY {
         * DEFINER | INVOKER } | COMMENT 'string'
         */
        String openingMarkers = this.quotedId + "(";
        String closingMarkers = this.quotedId + ")";
        String[] tokens = new String[] { "LANGUAGE", "NOT", "DETERMINISTIC", "CONTAINS", "NO", "READ", "MODIFIES", "SQL", "COMMENT", "BEGIN", "RETURN" };
        int startLookingAt = positionOfReturnKeyword + "RETURNS".length() + 1;
        int endOfReturn = -1;
        for (int i = 0; i < tokens.length; i++) {
            int nextEndOfReturn = StringUtils.indexOfIgnoreCase(startLookingAt, procedureDefn, tokens[i], openingMarkers, closingMarkers,
                    this.session.getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
            if (nextEndOfReturn != -1) {
                if (endOfReturn == -1 || nextEndOfReturn < endOfReturn) {
                    endOfReturn = nextEndOfReturn;
                }
            }
        }

        if (endOfReturn != -1) {
            return endOfReturn;
        }

        // Label?
        endOfReturn = StringUtils.indexOfIgnoreCase(startLookingAt, procedureDefn, ":", openingMarkers, closingMarkers,
                this.session.getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__BSE_MRK_COM_MYM_HNT_WS);

        if (endOfReturn != -1) {
            // seek back until whitespace
            for (int i = endOfReturn; i > 0; i--) {
                if (Character.isWhitespace(procedureDefn.charAt(i))) {
                    return i;
                }
            }
        }

        // We can't parse it.
        throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.5"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR, getExceptionInterceptor());
    }

    /**
     * Parses the cascade option string and returns the DBMD constant that
     * represents it (for deletes)
     *
     * Please note that in MySQL, NO ACTION is the equivalent to RESTRICT.
     *
     * @param cascadeOptions
     *            the comment from 'SHOW TABLE STATUS'
     * @return the DBMD constant that represents the cascade option
     */
    private int getCascadeDeleteOption(String cascadeOptions) {
        int onDeletePos = cascadeOptions.indexOf("ON DELETE");

        if (onDeletePos != -1) {
            String deleteOptions = cascadeOptions.substring(onDeletePos, cascadeOptions.length());

            if (deleteOptions.startsWith("ON DELETE CASCADE")) {
                return importedKeyCascade;
            } else if (deleteOptions.startsWith("ON DELETE SET NULL")) {
                return importedKeySetNull;
            }
        }

        return importedKeyRestrict;
    }

    /**
     * Parses the cascade option string and returns the DBMD constant that
     * represents it (for Updates).
     *
     * Please note that in MySQL, NO ACTION is the equivalent to RESTRICT.
     *
     * @param cascadeOptions
     *            the comment from 'SHOW TABLE STATUS'
     * @return the DBMD constant that represents the cascade option
     */
    private int getCascadeUpdateOption(String cascadeOptions) {
        int onUpdatePos = cascadeOptions.indexOf("ON UPDATE");

        if (onUpdatePos != -1) {
            String updateOptions = cascadeOptions.substring(onUpdatePos, cascadeOptions.length());

            if (updateOptions.startsWith("ON UPDATE CASCADE")) {
                return importedKeyCascade;
            } else if (updateOptions.startsWith("ON UPDATE SET NULL")) {
                return importedKeySetNull;
            }
        }

        return importedKeyRestrict;
    }

    protected IteratorWithCleanup<String> getDatabaseIterator(String dbSpec) throws SQLException {
        if (dbSpec == null) {
            return this.nullDatabaseMeansCurrent.getValue()
                    ? new SingleStringIterator(storesLowerCaseIdentifiers() ? this.database.toLowerCase() : this.database)
                    : new StringListIterator(getDatabases());
        }
        if (storesLowerCaseIdentifiers()) {
            dbSpec = dbSpec.toLowerCase();
        }
        return new SingleStringIterator(this.pedantic ? dbSpec : StringUtils.unQuoteIdentifier(dbSpec, this.quotedId));
    }

    protected IteratorWithCleanup<String> getSchemaPatternIterator(String schemaPattern) throws SQLException {
        if (schemaPattern == null) {
            return this.nullDatabaseMeansCurrent.getValue() ? new SingleStringIterator(this.database) : new StringListIterator(getDatabases());
        }
        return new StringListIterator(getDatabases(schemaPattern));
    }

    /**
     * Retrieves the database names available on this server. The results are ordered by database name.
     *
     * @return list of database names
     * @throws SQLException
     *             if an error occurs
     */
    protected List<String> getDatabases() throws SQLException {
        return getDatabases(null);
    }

    /**
     * Retrieves the database names matching the dbPattern available on this server. The results are ordered by database name.
     *
     * @param dbPattern
     *            database name pattern
     * @return list of database names
     * @throws SQLException
     *             if an error occurs
     */
    protected List<String> getDatabases(String dbPattern) throws SQLException {
        final String dbFilter = this.pedantic ? dbPattern : StringUtils.unQuoteIdentifier(dbPattern, this.quotedId);

        PreparedStatement pStmt = null;
        ResultSet rs = null;

        try {
            StringBuilder query = new StringBuilder("SHOW DATABASES");
            if (dbFilter != null) {
                query.append(" LIKE ?");
            }
            pStmt = prepareMetaDataSafeStatement(query.toString());
            if (dbFilter != null) {
                pStmt.setString(1, dbFilter);
            }
            rs = pStmt.executeQuery();

            int dbCount = 0;
            if (rs.last()) {
                dbCount = rs.getRow();
                rs.beforeFirst();
            }

            List<String> resultsAsList = new ArrayList<>(dbCount);
            while (rs.next()) {
                resultsAsList.add(rs.getString(1));
            }
            Collections.sort(resultsAsList);
            return resultsAsList;

        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    AssertionFailedException.shouldNotHappen(e);
                }
                rs = null;
            }
            if (pStmt != null) {
                try {
                    pStmt.close();
                } catch (SQLException e) {
                    AssertionFailedException.shouldNotHappen(e);
                }
                pStmt = null;
            }
        }
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        List<String> resultsAsList = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? new ArrayList<>() : getDatabases();

        Field[] fields = new Field[1];
        fields[0] = new Field("", "TABLE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 0);

        ArrayList<Row> rows = new ArrayList<>(resultsAsList.size());
        for (String cat : resultsAsList) {
            byte[][] row = new byte[1][];
            row[0] = s2b(cat);
            rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public String getCatalogSeparator() throws SQLException {
        return ".";
    }

    @Override
    public String getCatalogTerm() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? "CATALOG" : "database";
    }

    protected String getDatabase(String catalog, String schema) {
        if (this.databaseTerm.getValue() == DatabaseTerm.SCHEMA) {
            return schema == null && this.nullDatabaseMeansCurrent.getValue() ? this.database : schema;
        }
        return catalog == null && this.nullDatabaseMeansCurrent.getValue() ? this.database : catalog;
    }

    protected Field[] getColumnPrivilegesFields() {
        Field[] fields = new Field[8];
        fields[0] = new Field("", "TABLE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "TABLE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 1);
        fields[2] = new Field("", "TABLE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "COLUMN_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 64);
        fields[4] = new Field("", "GRANTOR", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 77);
        fields[5] = new Field("", "GRANTEE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 77);
        fields[6] = new Field("", "PRIVILEGE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 64);
        fields[7] = new Field("", "IS_GRANTABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 3);
        return fields;
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException {
        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String dbFromTerm = getDatabase(catalog, schema);
        final String dbFilter = this.pedantic ? dbFromTerm : StringUtils.unQuoteIdentifier(dbFromTerm, this.quotedId);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);
        final String columnNameFilter = this.pedantic ? columnNamePattern : StringUtils.unQuoteIdentifier(columnNamePattern, this.quotedId);

        StringBuilder query = new StringBuilder("SELECT c.host, c.db, t.grantor, c.user, c.table_name, c.column_name, c.column_priv");
        query.append(" FROM mysql.columns_priv c, mysql.tables_priv t");
        query.append(" WHERE c.host = t.host AND c.db = t.db AND c.table_name = t.table_name");
        if (dbFilter != null) {
            query.append(" AND c.db = ?");
        }
        query.append(" AND c.table_name = ?");
        if (columnNameFilter != null) {
            query.append(" AND c.column_name LIKE ?");
        }

        PreparedStatement pStmt = null;
        ResultSet rs = null;
        ArrayList<Row> rows = new ArrayList<>();

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, storesLowerCaseIdentifiers() ? dbFilter.toLowerCase() : dbFilter);
            }
            pStmt.setString(nextId++, storesLowerCaseIdentifiers() ? tableFilter.toLowerCase() : tableFilter);
            if (columnNameFilter != null) {
                pStmt.setString(nextId, columnNameFilter);
            }

            rs = pStmt.executeQuery();

            while (rs.next()) {
                String host = rs.getString(1);
                String db = rs.getString(2);
                String grantor = rs.getString(3);
                String user = rs.getString(4);
                if (user == null || user.length() == 0) {
                    user = "%";
                }
                StringBuilder fullUser = new StringBuilder(user);
                if (host != null && this.useHostsInPrivileges) {
                    fullUser.append("@");
                    fullUser.append(host);
                }
                String tableName = rs.getString(5);
                String columnName = rs.getString(6);
                String allPrivileges = rs.getString(7);

                if (allPrivileges != null) {
                    allPrivileges = allPrivileges.toUpperCase(Locale.ENGLISH);

                    StringTokenizer st = new StringTokenizer(allPrivileges, ",");
                    while (st.hasMoreTokens()) {
                        String privilege = st.nextToken().trim();
                        byte[][] row = new byte[8][];
                        row[0] = dbMapsToSchema ? s2b("def") : s2b(db);
                        row[1] = dbMapsToSchema ? s2b(db) : null;
                        row[2] = s2b(tableName);
                        row[3] = s2b(columnName);
                        row[4] = grantor != null ? s2b(grantor) : null;
                        row[5] = s2b(fullUser.toString());
                        row[6] = s2b(privilege);
                        row[7] = null;
                        rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                    }
                }
            }
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (Exception ex) {
                }
                rs = null;
            }
            if (pStmt != null) {
                try {
                    pStmt.close();
                } catch (Exception ex) {
                }
                pStmt = null;
            }
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(getColumnPrivilegesFields())));
    }

    @Override
    public ResultSet getColumns(final String catalog, final String schemaPattern, final String tableNamePattern, String columnNamePattern) throws SQLException {
        Field[] fields = createColumnsFields();
        final List<Row> rows = new ArrayList<>();

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String columnNameFilter = this.pedantic ? columnNamePattern : StringUtils.unQuoteIdentifier(columnNamePattern, this.quotedId);

        try (final Statement stmt = this.conn.getMetadataSafeStatement()) {
            SortedMap<String, List<String>> tableNamesPerDb = new TreeMap<>();
            ResultSet tables = null;
            try {
                tables = getTables(catalog, schemaPattern, tableNamePattern, null);
                while (tables.next()) {
                    final String db = tables.getString(dbMapsToSchema ? "TABLE_SCHEM" : "TABLE_CAT");
                    List<String> tableNames = tableNamesPerDb.get(db);
                    if (tableNames == null) {
                        tableNames = new ArrayList<>();
                    }
                    tableNames.add(tables.getString("TABLE_NAME"));
                    tableNamesPerDb.put(db, tableNames);
                }
            } finally {
                if (tables != null) {
                    try {
                        tables.close();
                    } catch (Exception sqlEx) {
                        AssertionFailedException.shouldNotHappen(sqlEx);
                    }
                    tables = null;
                }
            }

            for (String dbName : tableNamesPerDb.keySet()) {
                for (String tableName : tableNamesPerDb.get(dbName)) {
                    ResultSet rs = null;
                    try {
                        StringBuilder query = new StringBuilder("SHOW FULL COLUMNS FROM ");
                        query.append(StringUtils.quoteIdentifier(tableName, DatabaseMetaData.this.quotedId, true));
                        query.append(" FROM ");
                        query.append(StringUtils.quoteIdentifier(dbName, DatabaseMetaData.this.quotedId, true));

                        // Find column ordinals if column name pattern is not '%'.
                        // SHOW COLUMNS does not include ordinal information so another round trip is required to return all columns in the table and compute
                        // their ordinal positions.
                        boolean fixUpOrdinalsRequired = false;
                        Map<String, Integer> ordinalFixUpMap = null;

                        if (columnNameFilter != null && !columnNameFilter.equals("%")) {
                            fixUpOrdinalsRequired = true;
                            ordinalFixUpMap = new HashMap<>();
                            rs = stmt.executeQuery(query.toString());
                            int ordinalPos = 1;
                            while (rs.next()) {
                                String columnName = rs.getString("Field");
                                ordinalFixUpMap.put(columnName, ordinalPos++);
                            }
                            rs.close();
                        }

                        if (columnNameFilter != null) {
                            query.append(" LIKE ");
                            query.append(StringUtils.quoteIdentifier(columnNameFilter, "'", true));
                        }
                        rs = stmt.executeQuery(query.toString());

                        int ordPos = 1;
                        while (rs.next()) {
                            TypeDescriptor typeDesc = new TypeDescriptor(rs.getString("Type"), rs.getString("Null"));

                            byte[][] row = new byte[24][];
                            row[0] = DatabaseMetaData.this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? s2b("def") : s2b(dbName); // TABLE_CAT
                            row[1] = DatabaseMetaData.this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? s2b(dbName) : null;       // TABLE_SCHEM
                            row[2] = s2b(tableName);                                                                                  // TABLE_NAME
                            row[3] = rs.getBytes("Field");
                            row[4] = Short.toString(typeDesc.mysqlType == MysqlType.YEAR && !DatabaseMetaData.this.yearIsDateType ? Types.SMALLINT
                                    : (short) typeDesc.mysqlType.getJdbcType()).getBytes();                                           // DATA_TYPE (jdbc)
                            row[5] = s2b(typeDesc.mysqlType.getName());                                                               // TYPE_NAME (native)
                            if (typeDesc.columnSize == null) {                                                                        // COLUMN_SIZE
                                row[6] = null;
                            } else {
                                String collation = rs.getString("Collation");
                                int mbminlen = 1;
                                if (collation != null) {
                                    // not null collation could only be returned by server for character types, so we don't need to check type name
                                    if (collation.indexOf("ucs2") > -1 || collation.indexOf("utf16") > -1) {
                                        mbminlen = 2;
                                    } else if (collation.indexOf("utf32") > -1) {
                                        mbminlen = 4;
                                    }
                                }
                                row[6] = mbminlen == 1 ? s2b(typeDesc.columnSize.toString()) : s2b(((Integer) (typeDesc.columnSize / mbminlen)).toString());
                            }
                            row[7] = s2b(Integer.toString(typeDesc.bufferLength));
                            row[8] = typeDesc.decimalDigits == null ? null : s2b(typeDesc.decimalDigits.toString());
                            row[9] = s2b(Integer.toString(typeDesc.numPrecRadix));
                            row[10] = s2b(Integer.toString(typeDesc.nullability));

                            // Doesn't always have this field, depending on version
                            try {
                                row[11] = rs.getBytes("Comment");                                                                     // REMARK column
                            } catch (Exception e) {
                                row[11] = new byte[0];                                                                                // REMARK column
                            }

                            row[12] = rs.getBytes("Default");                                                                         // COLUMN_DEF
                            row[13] = new byte[] { (byte) '0' };                                                                      // SQL_DATA_TYPE
                            row[14] = new byte[] { (byte) '0' };                                                                      // SQL_DATE_TIME_SUB

                            if (StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "CHAR") != -1
                                    || StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "BLOB") != -1
                                    || StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "TEXT") != -1
                                    || StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "ENUM") != -1
                                    || StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "SET") != -1
                                    || StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "BINARY") != -1) {
                                row[15] = row[6];                                                                                     // CHAR_OCTET_LENGTH
                            } else {
                                row[15] = null;
                            }

                            if (!fixUpOrdinalsRequired) {
                                row[16] = Integer.toString(ordPos++).getBytes();                                                      // ORDINAL_POSITION
                            } else {
                                String origColName = rs.getString("Field");
                                Integer realOrdinal = ordinalFixUpMap.get(origColName);

                                if (realOrdinal != null) {
                                    row[16] = realOrdinal.toString().getBytes();                                                      // ORDINAL_POSITION
                                } else {
                                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.10"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                            getExceptionInterceptor());
                                }
                            }

                            row[17] = s2b(typeDesc.isNullable);

                            // We don't support REF or DISTINCT types
                            row[18] = null;
                            row[19] = null;
                            row[20] = null;
                            row[21] = null;

                            row[22] = s2b("");

                            String extra = rs.getString("Extra");
                            if (extra != null) {
                                row[22] = s2b(StringUtils.indexOfIgnoreCase(extra, "auto_increment") != -1 ? "YES" : "NO");
                                row[23] = s2b(StringUtils.indexOfIgnoreCase(extra, "generated") != -1 ? "YES" : "NO");
                            }

                            rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                        }
                    } finally {
                        if (rs != null) {
                            try {
                                rs.close();
                            } catch (Exception e) {
                            }
                            rs = null;
                        }
                    }
                }
            }
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    protected Field[] createColumnsFields() {
        Field[] fields = new Field[24];
        fields[0] = new Field("", "TABLE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[1] = new Field("", "TABLE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[2] = new Field("", "TABLE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[3] = new Field("", "COLUMN_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[4] = new Field("", "DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 5);
        fields[5] = new Field("", "TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 16); // TODO why is it 16 bytes long? we have longer types specifications
        fields[6] = new Field("", "COLUMN_SIZE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT,
                Integer.toString(Integer.MAX_VALUE).length());
        fields[7] = new Field("", "BUFFER_LENGTH", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[8] = new Field("", "DECIMAL_DIGITS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[9] = new Field("", "NUM_PREC_RADIX", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[10] = new Field("", "NULLABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[11] = new Field("", "REMARKS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[12] = new Field("", "COLUMN_DEF", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[13] = new Field("", "SQL_DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[14] = new Field("", "SQL_DATETIME_SUB", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[15] = new Field("", "CHAR_OCTET_LENGTH", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT,
                Integer.toString(Integer.MAX_VALUE).length());
        fields[16] = new Field("", "ORDINAL_POSITION", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[17] = new Field("", "IS_NULLABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 3);
        fields[18] = new Field("", "SCOPE_CATALOG", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[19] = new Field("", "SCOPE_SCHEMA", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[20] = new Field("", "SCOPE_TABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[21] = new Field("", "SOURCE_DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 10);
        fields[22] = new Field("", "IS_AUTOINCREMENT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 3);
        fields[23] = new Field("", "IS_GENERATEDCOLUMN", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 3);
        return fields;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return this.conn;
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

        final Field[] fields = createFkMetadataFields();
        final ArrayList<Row> rows = new ArrayList<>();
        final Statement stmt = this.conn.getMetadataSafeStatement();

        try {
            new IterateBlock<String>(getDatabaseIterator(foreignDbFilter)) {

                @Override
                void forEach(String db) throws SQLException {
                    ResultSet fkRs = null;
                    try {
                        // Get foreign key information for table.
                        fkRs = extractForeignKeyFromCreateTable(db, normalizeCase(foreignTableFilter));

                        // Parse imported foreign key information.
                        while (fkRs.next()) {
                            String tableType = fkRs.getString("Type");
                            if (tableType != null && (tableType.equalsIgnoreCase("innodb") || tableType.equalsIgnoreCase(SUPPORTS_FK))) {
                                String comment = fkRs.getString("Comment").trim();
                                if (comment != null) {
                                    StringTokenizer commentTokens = new StringTokenizer(comment, ";", false);
                                    if (commentTokens.hasMoreTokens()) {
                                        commentTokens.nextToken(); // Skip first token, originally a InnoDB comment.
                                    }

                                    while (commentTokens.hasMoreTokens()) {
                                        String keys = commentTokens.nextToken();
                                        ReferencingAndReferencedColumns parsedInfo = parseTableStatusIntoReferencingAndReferencedColumns(keys);

                                        String foreignTableName = fkRs.getString("Name");
                                        // Skip foreign key if it doesn't refer to the right table.
                                        if (!foreignTableName.contentEquals(normalizeCase(foreignTableFilter))
                                                || !parsedInfo.referencedTable.contentEquals(normalizeCase(parentTableFilter))
                                                || parentDbFilter != null && !parsedInfo.referencedDatabase.contentEquals(normalizeCase(parentDbFilter))) {
                                            continue;
                                        }

                                        int keySeq = 1;
                                        Iterator<String> referencingColumns = parsedInfo.referencingColumnsList.iterator();
                                        Iterator<String> referencedColumns = parsedInfo.referencedColumnsList.iterator();
                                        while (referencingColumns.hasNext()) {
                                            // One row for each table between parenthesis.
                                            byte[][] row = new byte[14][];
                                            row[0] = dbMapsToSchema ? s2b("def") : s2b(parsedInfo.referencedDatabase); // PKTABLE_CAT
                                            row[1] = dbMapsToSchema ? s2b(parsedInfo.referencedDatabase) : null;       // PKTABLE_SCHEM
                                            row[2] = s2b(parsedInfo.referencedTable);                                  // PKTABLE_NAME
                                            String referencedColumn = StringUtils.unQuoteIdentifier(referencedColumns.next(), DatabaseMetaData.this.quotedId);
                                            row[3] = s2b(referencedColumn);                                            // PKCOLUMN_NAME
                                            row[4] = dbMapsToSchema ? s2b("def") : s2b(db);                            // FKTABLE_CAT
                                            row[5] = dbMapsToSchema ? s2b(db) : null;                                  // FKTABLE_SCHEM
                                            row[6] = s2b(foreignTableName);                                            // FKTABLE_NAME
                                            String referencingColumn = StringUtils.unQuoteIdentifier(referencingColumns.next(), DatabaseMetaData.this.quotedId);
                                            row[7] = s2b(referencingColumn);                                           // FKCOLUMN_NAME
                                            row[8] = Integer.toString(keySeq).getBytes();                              // KEY_SEQ
                                            int[] actions = getForeignKeyActions(keys);
                                            row[9] = Integer.toString(actions[1]).getBytes();                          // UPDATE_RULE
                                            row[10] = Integer.toString(actions[0]).getBytes();                         // DELETE_RULE
                                            row[11] = s2b(parsedInfo.constraintName);                                  // FK_NAME
                                            row[12] = null;                                                            // PK_NAME
                                            row[13] = Integer.toString(importedKeyNotDeferrable).getBytes();           // DEFERRABILITY
                                            rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                                            keySeq++;
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        if (fkRs != null) {
                            try {
                                fkRs.close();
                            } catch (Exception sqlEx) {
                                AssertionFailedException.shouldNotHappen(sqlEx);
                            }
                            fkRs = null;
                        }
                    }
                }

            }.doForAll();
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    protected Field[] createFkMetadataFields() {
        Field[] fields = new Field[14];
        fields[0] = new Field("", "PKTABLE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[1] = new Field("", "PKTABLE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[2] = new Field("", "PKTABLE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[3] = new Field("", "PKCOLUMN_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[4] = new Field("", "FKTABLE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[5] = new Field("", "FKTABLE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[6] = new Field("", "FKTABLE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[7] = new Field("", "FKCOLUMN_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[8] = new Field("", "KEY_SEQ", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 2);
        fields[9] = new Field("", "UPDATE_RULE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 2);
        fields[10] = new Field("", "DELETE_RULE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 2);
        fields[11] = new Field("", "FK_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[12] = new Field("", "PK_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[13] = new Field("", "DEFERRABILITY", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 2);
        return fields;
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return this.conn.getServerVersion().getMajor();
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return this.conn.getServerVersion().getMinor();
    }

    @Override
    public String getDatabaseProductName() throws SQLException {
        return "MySQL";
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        return this.conn.getServerVersion().toString();
    }

    @Override
    public int getDefaultTransactionIsolation() throws SQLException {
        return Connection.TRANSACTION_REPEATABLE_READ;
    }

    @Override
    public int getDriverMajorVersion() {
        return NonRegisteringDriver.getMajorVersionInternal();
    }

    @Override
    public int getDriverMinorVersion() {
        return NonRegisteringDriver.getMinorVersionInternal();
    }

    @Override
    public String getDriverName() throws SQLException {
        return Constants.CJ_NAME;
    }

    @Override
    public String getDriverVersion() throws SQLException {
        return Constants.CJ_FULL_NAME + " (Revision: " + Constants.CJ_REVISION + ")";
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, final String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFromTerm = getDatabase(catalog, schema);
        final String dbFilter = this.pedantic ? dbFromTerm : StringUtils.unQuoteIdentifier(dbFromTerm, this.quotedId);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);

        final Field[] fields = createFkMetadataFields();
        final ArrayList<Row> rows = new ArrayList<>();
        final Statement stmt = this.conn.getMetadataSafeStatement();

        try {
            new IterateBlock<String>(new StringListIterator(getDatabases())) {

                @Override
                void forEach(String db) throws SQLException {
                    ResultSet fkRs = null;
                    try {
                        // Get foreign key information for table.
                        fkRs = extractForeignKeyFromCreateTable(db, null);

                        // Parse imported foreign key information.
                        while (fkRs.next()) {
                            String tableType = fkRs.getString("Type");
                            if (tableType != null && (tableType.equalsIgnoreCase("innodb") || tableType.equalsIgnoreCase(SUPPORTS_FK))) {
                                String comment = fkRs.getString("Comment").trim();
                                if (comment != null) {
                                    StringTokenizer commentTokens = new StringTokenizer(comment, ";", false);
                                    if (commentTokens.hasMoreTokens()) {
                                        commentTokens.nextToken(); // Skip first token, originally a InnoDB comment.
                                    }

                                    while (commentTokens.hasMoreTokens()) {
                                        String keys = commentTokens.nextToken();
                                        populateKeyResults(normalizeCase(dbFilter), normalizeCase(tableFilter), db, fkRs.getString("Name"), keys, true, rows);
                                    }
                                }
                            }
                        }
                    } finally {
                        if (fkRs != null) {
                            try {
                                fkRs.close();
                            } catch (SQLException sqlEx) {
                                AssertionFailedException.shouldNotHappen(sqlEx);
                            }
                            fkRs = null;
                        }
                    }
                }

            }.doForAll();
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public String getExtraNameCharacters() throws SQLException {
        return "$";
    }

    @Override
    public String getIdentifierQuoteString() throws SQLException {
        // NOTE: A JDBC compliant driver always uses a double quote character.
        return this.session.getIdentifierQuoteString();
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, final String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFilter = getDatabase(catalog, schema);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);

        final Field[] fields = createFkMetadataFields();
        final ArrayList<Row> rows = new ArrayList<>();
        final Statement stmt = this.conn.getMetadataSafeStatement();

        try {
            new IterateBlock<String>(getDatabaseIterator(dbFilter)) {

                @Override
                void forEach(String db) throws SQLException {
                    ResultSet fkRs = null;
                    try {
                        // Get foreign key information for table.
                        fkRs = extractForeignKeyFromCreateTable(db, normalizeCase(tableFilter));

                        // Parse imported foreign key information.
                        while (fkRs.next()) {
                            String tableType = fkRs.getString("Type");
                            if (tableType != null && (tableType.equalsIgnoreCase("innodb") || tableType.equalsIgnoreCase(SUPPORTS_FK))) {
                                String comment = fkRs.getString("Comment").trim();
                                if (comment != null) {
                                    StringTokenizer commentTokens = new StringTokenizer(comment, ";", false);
                                    if (commentTokens.hasMoreTokens()) {
                                        commentTokens.nextToken(); // Skip first token, originally a InnoDB comment.
                                    }

                                    while (commentTokens.hasMoreTokens()) {
                                        String keys = commentTokens.nextToken();
                                        populateKeyResults(null, null, db, fkRs.getString("NAME"), keys, false, rows);
                                    }
                                }
                            }
                        }
                    } finally {
                        if (fkRs != null) {
                            try {
                                fkRs.close();
                            } catch (SQLException sqlEx) {
                                AssertionFailedException.shouldNotHappen(sqlEx);
                            }
                            fkRs = null;
                        }
                    }
                }

            }.doForAll();
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, final String table, final boolean unique, boolean approximate) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        String dbFilter = getDatabase(catalog, schema);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);

        Field[] fields = createIndexInfoFields();

        final ArrayList<Row> rows = new ArrayList<>();
        final Statement stmt = this.conn.getMetadataSafeStatement();

        try {
            new IterateBlock<String>(getDatabaseIterator(dbFilter)) {

                @Override
                void forEach(String db) throws SQLException {
                    ResultSet rs = null;
                    try {
                        // MySQL stores index information in the fields: Table Non_unique Key_name Seq_in_index Column_name Collation Cardinality Sub_part
                        StringBuilder query = new StringBuilder("SHOW INDEX FROM ");
                        query.append(StringUtils.quoteIdentifier(tableFilter, DatabaseMetaData.this.quotedId, DatabaseMetaData.this.pedantic));
                        query.append(" FROM ");
                        query.append(StringUtils.quoteIdentifier(db, DatabaseMetaData.this.quotedId, true));

                        try {
                            rs = stmt.executeQuery(query.toString());
                        } catch (SQLException e) {
                            String sqlState = e.getSQLState(); // Ignore exception if SQLState is 42S02 or 42000 - table/database doesn't exist.
                            int errorCode = e.getErrorCode(); // Ignore exception if ErrorCode is 1146, 1109, or 1149 - table/database doesn't exist.

                            if (!("42S02".equals(sqlState)
                                    && (errorCode == MysqlErrorNumbers.ER_NO_SUCH_TABLE || errorCode == MysqlErrorNumbers.ER_UNKNOWN_TABLE)
                                    || "42000".equals(sqlState) && errorCode == MysqlErrorNumbers.ER_BAD_DB_ERROR)) {
                                throw e;
                            }
                        }

                        final SortedMap<IndexMetaDataKey, Row> sortedRows = new TreeMap<>();
                        while (rs != null && rs.next()) {
                            byte[][] row = new byte[14][];
                            row[0] = dbMapsToSchema ? s2b("def") : s2b(db);       // TABLE_CAT
                            row[1] = dbMapsToSchema ? s2b(db) : null;             // TABLE_SCHEM
                            row[2] = rs.getBytes("Table");                        // TABLE_NAME
                            boolean indexIsUnique = rs.getInt("Non_unique") == 0;
                            row[3] = !indexIsUnique ? s2b("true") : s2b("false"); // NON_UNIQUE
                            row[4] = null;                                        // INDEX_QUALIFIER
                            row[5] = rs.getBytes("Key_name");                     // INDEX_NAME
                            short indexType = tableIndexOther;
                            row[6] = Integer.toString(indexType).getBytes();      // TYPE
                            row[7] = rs.getBytes("Seq_in_index");                 // ORDINAL_POSITION
                            row[8] = rs.getBytes("Column_name");                  // COLUMN_NAME
                            row[9] = rs.getBytes("Collation");                    // ASC_OR_DESC
                            long cardinality = rs.getLong("Cardinality");
                            row[10] = s2b(String.valueOf(cardinality));           // CARDINALITY
                            row[11] = s2b("0");                                   // PAGES
                            row[12] = null;                                       // FILTER_CONDITION

                            IndexMetaDataKey indexInfoKey = new IndexMetaDataKey(!indexIsUnique, indexType, rs.getString("Key_name").toLowerCase(),
                                    rs.getShort("Seq_in_index"));

                            if (unique) {
                                if (indexIsUnique) {
                                    sortedRows.put(indexInfoKey, new ByteArrayRow(row, getExceptionInterceptor()));
                                }
                            } else {
                                // All rows match
                                sortedRows.put(indexInfoKey, new ByteArrayRow(row, getExceptionInterceptor()));
                            }
                        }

                        rows.addAll(sortedRows.values());
                    } finally {
                        if (rs != null) {
                            try {
                                rs.close();
                            } catch (Exception ex) {
                            }
                            rs = null;
                        }
                    }
                }

            }.doForAll();
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    protected Field[] createIndexInfoFields() {
        Field[] fields = new Field[13];
        fields[0] = new Field("", "TABLE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[1] = new Field("", "TABLE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[2] = new Field("", "TABLE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[3] = new Field("", "NON_UNIQUE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.BOOLEAN, 4);
        fields[4] = new Field("", "INDEX_QUALIFIER", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 1);
        fields[5] = new Field("", "INDEX_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[6] = new Field("", "TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 32);
        fields[7] = new Field("", "ORDINAL_POSITION", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 5);
        fields[8] = new Field("", "COLUMN_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[9] = new Field("", "ASC_OR_DESC", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 1);
        fields[10] = new Field("", "CARDINALITY", this.metadataCollationIndex, this.metadataEncoding, MysqlType.BIGINT, 20);
        fields[11] = new Field("", "PAGES", this.metadataCollationIndex, this.metadataEncoding, MysqlType.BIGINT, 20);
        fields[12] = new Field("", "FILTER_CONDITION", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        return fields;
    }

    @Override
    public int getJDBCMajorVersion() throws SQLException {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() throws SQLException {
        return 2;
    }

    @Override
    public int getMaxBinaryLiteralLength() throws SQLException {
        return 16777208;
    }

    @Override
    public int getMaxCatalogNameLength() throws SQLException {
        return 32;
    }

    @Override
    public int getMaxCharLiteralLength() throws SQLException {
        return 16777208;
    }

    @Override
    public int getMaxColumnNameLength() throws SQLException {
        return 64;
    }

    @Override
    public int getMaxColumnsInGroupBy() throws SQLException {
        return 64;
    }

    @Override
    public int getMaxColumnsInIndex() throws SQLException {
        return 16;
    }

    @Override
    public int getMaxColumnsInOrderBy() throws SQLException {
        return 64;
    }

    @Override
    public int getMaxColumnsInSelect() throws SQLException {
        return 256;
    }

    @Override
    public int getMaxColumnsInTable() throws SQLException {
        return 512;
    }

    @Override
    public int getMaxConnections() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() throws SQLException {
        return 64;
    }

    @Override
    public int getMaxIndexLength() throws SQLException {
        return 256;
    }

    @Override
    public int getMaxProcedureNameLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxRowSize() throws SQLException {
        return Integer.MAX_VALUE - 8; // Max buffer size - HEADER
    }

    @Override
    public int getMaxSchemaNameLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxStatementLength() throws SQLException {
        return maxBufferSize - 4; // Max buffer - header
    }

    @Override
    public int getMaxStatements() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() throws SQLException {
        return 64;
    }

    @Override
    public int getMaxTablesInSelect() throws SQLException {
        return 256;
    }

    @Override
    public int getMaxUserNameLength() throws SQLException {
        return 16;
    }

    @Override
    public String getNumericFunctions() throws SQLException {
        return "ABS,ACOS,ASIN,ATAN,ATAN2,BIT_COUNT,CEILING,COS,COT,DEGREES,EXP,FLOOR,LOG,LOG10,MAX,MIN,MOD,PI,POW,"
                + "POWER,RADIANS,RAND,ROUND,SIN,SQRT,TAN,TRUNCATE";
    }

    protected Field[] getPrimaryKeysFields() {
        Field[] fields = new Field[6];
        fields[0] = new Field("", "TABLE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[1] = new Field("", "TABLE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[2] = new Field("", "TABLE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[3] = new Field("", "COLUMN_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[4] = new Field("", "KEY_SEQ", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 5);
        fields[5] = new Field("", "PK_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        return fields;
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, final String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String dbFilter = getDatabase(catalog, schema);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);

        final ArrayList<Row> rows = new ArrayList<>();
        final Statement stmt = this.conn.getMetadataSafeStatement();

        try {
            new IterateBlock<String>(getDatabaseIterator(dbFilter)) {

                @Override
                void forEach(String db) throws SQLException {
                    ResultSet rs = null;
                    try {
                        StringBuilder query = new StringBuilder("SHOW KEYS FROM ");
                        query.append(StringUtils.quoteIdentifier(tableFilter, DatabaseMetaData.this.quotedId, true));
                        query.append(" FROM ");
                        query.append(StringUtils.quoteIdentifier(db, DatabaseMetaData.this.quotedId, true));

                        try {
                            rs = stmt.executeQuery(query.toString());
                        } catch (SQLException e) {
                            String sqlState = e.getSQLState(); // Ignore exception if SQLState is 42S02 or 42000 - table/database doesn't exist.
                            int errorCode = e.getErrorCode(); // Ignore exception if ErrorCode is 1146, 1109, or 1149 - table/database doesn't exist.

                            if (!("42S02".equals(sqlState)
                                    && (errorCode == MysqlErrorNumbers.ER_NO_SUCH_TABLE || errorCode == MysqlErrorNumbers.ER_UNKNOWN_TABLE)
                                    || "42000".equals(sqlState) && errorCode == MysqlErrorNumbers.ER_BAD_DB_ERROR)) {
                                throw e;
                            }
                        }

                        TreeMap<String, byte[][]> sortMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                        while (rs != null && rs.next()) {
                            String keyType = rs.getString("Key_name");

                            if (keyType != null) {
                                if (keyType.equalsIgnoreCase("PRIMARY") || keyType.equalsIgnoreCase("PRI")) {
                                    byte[][] row = new byte[6][];
                                    row[0] = dbMapsToSchema ? s2b("def") : s2b(db); // TABLE_CAT
                                    row[1] = dbMapsToSchema ? s2b(db) : null;       // TABLE_SCHEM
                                    row[2] = s2b(rs.getString("Table"));            // TABLE_NAME
                                    String columnName = rs.getString("Column_name");
                                    row[3] = s2b(columnName);                       // COLUMN_NAME
                                    row[4] = s2b(rs.getString("Seq_in_index"));     // KEY_SEQ
                                    row[5] = s2b(keyType);                          // PK_NAME
                                    sortMap.put(columnName, row);
                                }
                            }
                        }

                        for (byte[][] row : sortMap.values()) {
                            rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                        }
                    } finally {
                        if (rs != null) {
                            try {
                                rs.close();
                            } catch (Exception ex) {
                            }
                            rs = null;
                        }
                    }
                }

            }.doForAll();
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(getPrimaryKeysFields())));
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException {
        return getProcedureOrFunctionColumns(createProcedureColumnsFields(), catalog, schemaPattern, procedureNamePattern, columnNamePattern, true,
                this.conn.getPropertySet().getBooleanProperty(PropertyKey.getProceduresReturnsFunctions).getValue());
    }

    protected Field[] createProcedureColumnsFields() {
        Field[] fields = new Field[20];
        fields[0] = new Field("", "PROCEDURE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 512);
        fields[1] = new Field("", "PROCEDURE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 512);
        fields[2] = new Field("", "PROCEDURE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 512);
        fields[3] = new Field("", "COLUMN_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 512);
        fields[4] = new Field("", "COLUMN_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 64);
        fields[5] = new Field("", "DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 6);
        fields[6] = new Field("", "TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 64);
        fields[7] = new Field("", "PRECISION", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12);
        fields[8] = new Field("", "LENGTH", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12);
        fields[9] = new Field("", "SCALE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 12);
        fields[10] = new Field("", "RADIX", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 6);
        fields[11] = new Field("", "NULLABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 6);
        fields[12] = new Field("", "REMARKS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 512);
        fields[13] = new Field("", "COLUMN_DEF", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 512);
        fields[14] = new Field("", "SQL_DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12);
        fields[15] = new Field("", "SQL_DATETIME_SUB", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12);
        fields[16] = new Field("", "CHAR_OCTET_LENGTH", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12);
        fields[17] = new Field("", "ORDINAL_POSITION", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12);
        fields[18] = new Field("", "IS_NULLABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 512);
        fields[19] = new Field("", "SPECIFIC_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 512);
        return fields;
    }

    protected ResultSet getProcedureOrFunctionColumns(Field[] fields, String catalog, String schemaPattern, String procedureOrFunctionNamePattern,
            String columnNamePattern, boolean returnProcedures, boolean returnFunctions) throws SQLException {
        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;

        List<ComparableWrapper<TableDbObjectKey, ProcedureType>> procsOrFuncsToExtract = new ArrayList<>();
        ResultSet procsAndOrFuncsRs = null;

        try {
            procsAndOrFuncsRs = getProceduresAndOrFunctions(createFieldMetadataForGetProcedures(), catalog, schemaPattern, procedureOrFunctionNamePattern,
                    returnProcedures, returnFunctions);

            boolean hasResults = false;
            while (procsAndOrFuncsRs.next()) {
                procsOrFuncsToExtract.add(new ComparableWrapper<>(
                        new TableDbObjectKey(dbMapsToSchema ? procsAndOrFuncsRs.getString("PROCEDURE_SCHEM") : procsAndOrFuncsRs.getString("PROCEDURE_CAT"),
                                procsAndOrFuncsRs.getString("PROCEDURE_NAME")),
                        procsAndOrFuncsRs.getShort("PROCEDURE_TYPE") == procedureNoResult ? PROCEDURE : FUNCTION));
                hasResults = true;
            }

            // FIX for Bug#56305, allowing the code to proceed with empty fields causing NPE later
            if (!hasResults) {
                // throw SQLError.createSQLException(
                // "User does not have access to metadata required to determine " +
                // "stored procedure parameter types. If rights can not be granted, configure connection with \"noAccessToProcedureBodies=true\" " +
                // "to have driver generate parameters that represent INOUT strings irregardless of actual parameter types.",
                // MysqlErrorNumbers.SQL_STATE_GENERAL_ERROR, getExceptionInterceptor());
            } else {
                Collections.sort(procsOrFuncsToExtract);
            }
        } finally {
            SQLException rethrowSqlEx = null;
            if (procsAndOrFuncsRs != null) {
                try {
                    procsAndOrFuncsRs.close();
                } catch (SQLException sqlEx) {
                    rethrowSqlEx = sqlEx;
                }
                procsAndOrFuncsRs = null;
            }
            if (rethrowSqlEx != null) {
                throw rethrowSqlEx;
            }
        }

        ArrayList<Row> rows = new ArrayList<>();
        for (ComparableWrapper<TableDbObjectKey, ProcedureType> procOrFunc : procsOrFuncsToExtract) {
            final TableDbObjectKey dbObject = procOrFunc.getKey();
            final ProcedureType procType = procOrFunc.getValue();

            getProcedureOrFunctionParameterTypes(dbObject.dbName, dbObject.objectName, procType, columnNamePattern, rows,
                    fields.length == 17 /* for getFunctionColumns */);
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        return getProceduresAndOrFunctions(createFieldMetadataForGetProcedures(), catalog, schemaPattern, procedureNamePattern, true,
                this.conn.getPropertySet().getBooleanProperty(PropertyKey.getProceduresReturnsFunctions).getValue());
    }

    protected Field[] createFieldMetadataForGetProcedures() {
        Field[] fields = new Field[9];
        fields[0] = new Field("", "PROCEDURE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[1] = new Field("", "PROCEDURE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[2] = new Field("", "PROCEDURE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[3] = new Field("", "reserved1", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[4] = new Field("", "reserved2", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[5] = new Field("", "reserved3", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[6] = new Field("", "REMARKS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[7] = new Field("", "PROCEDURE_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 6);
        fields[8] = new Field("", "SPECIFIC_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);

        return fields;
    }

    /**
     * @param fields
     *            fields
     * @param catalog
     *            catalog
     * @param schemaPattern
     *            schema pattern
     * @param procedureNamePattern
     *            procedure name pattern
     * @param includeProcedures
     *            true if procedures should be included into result
     * @param includeFunctions
     *            true if functions should be included into result
     * @return result set
     * @throws SQLException
     *             if a database access error occurs
     */
    protected ResultSet getProceduresAndOrFunctions(final Field[] fields, String catalog, String schemaPattern, final String procedureNamePattern,
            final boolean includeProcedures, final boolean includeFunctions) throws SQLException {
        final ArrayList<Row> rows = new ArrayList<>();

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String dbFilter = getDatabase(catalog, schemaPattern);
        final String procedureNameFilter = this.pedantic ? procedureNamePattern : StringUtils.unQuoteIdentifier(procedureNamePattern, this.quotedId);

        final List<ComparableWrapper<String, Row>> procedureRowsToSort = new ArrayList<>();

        new IterateBlock<String>(dbMapsToSchema ? getSchemaPatternIterator(dbFilter) : getDatabaseIterator(dbFilter)) {

            @Override
            void forEach(String db) throws SQLException {
                ResultSet proceduresRs = null;

                StringBuilder selectFromMySQLProcSQL = new StringBuilder();

                selectFromMySQLProcSQL.append("SELECT db, name, type, comment FROM mysql.proc WHERE");
                if (includeProcedures && !includeFunctions) {
                    selectFromMySQLProcSQL.append(" type = 'PROCEDURE' AND ");
                } else if (!includeProcedures && includeFunctions) {
                    selectFromMySQLProcSQL.append(" type = 'FUNCTION' AND ");
                }

                selectFromMySQLProcSQL.append(dbMapsToSchema ? " db LIKE ?" : " db = ?");

                if (!StringUtils.isNullOrEmpty(procedureNameFilter)) {
                    selectFromMySQLProcSQL.append(" AND name LIKE ?");
                }

                selectFromMySQLProcSQL.append(" ORDER BY name, type");

                PreparedStatement proceduresStmt = prepareMetaDataSafeStatement(selectFromMySQLProcSQL.toString());

                try {
                    /* Try using system tables first, as this is a little bit more efficient.... */
                    proceduresStmt.setString(1, storesLowerCaseIdentifiers() ? db.toLowerCase() : db);

                    if (!StringUtils.isNullOrEmpty(procedureNameFilter)) {
                        proceduresStmt.setString(2, procedureNameFilter);
                    }

                    try {
                        proceduresRs = proceduresStmt.executeQuery();

                        if (includeProcedures) {
                            convertToJdbcProcedureList(true, proceduresRs, procedureRowsToSort);
                        }

                        if (includeFunctions) {
                            convertToJdbcFunctionList(proceduresRs, procedureRowsToSort, fields);
                        }

                    } catch (SQLException e) {
                        // The mysql.proc table didn't exist in early MySQL versions and it's removed in MySQL 8.0,
                        // so use 'SHOW [FUNCTION|PROCEDURE] STATUS instead.

                        // Functions first:
                        if (includeFunctions) {
                            proceduresStmt.close();

                            String sql = "SHOW FUNCTION STATUS WHERE " + (dbMapsToSchema ? "Db LIKE ?" : "Db = ?");
                            if (!StringUtils.isNullOrEmpty(procedureNameFilter)) {
                                sql += " AND Name LIKE ?";
                            }
                            proceduresStmt = prepareMetaDataSafeStatement(sql);
                            proceduresStmt.setString(1, db);
                            if (!StringUtils.isNullOrEmpty(procedureNameFilter)) {
                                proceduresStmt.setString(2, procedureNameFilter);
                            }
                            proceduresRs = proceduresStmt.executeQuery();

                            convertToJdbcFunctionList(proceduresRs, procedureRowsToSort, fields);
                        }

                        // Procedures next:
                        if (includeProcedures) {
                            proceduresStmt.close();

                            String sql = "SHOW PROCEDURE STATUS WHERE " + (dbMapsToSchema ? "Db LIKE ?" : "Db = ?");
                            if (!StringUtils.isNullOrEmpty(procedureNameFilter)) {
                                sql += " AND Name LIKE ?";
                            }
                            proceduresStmt = prepareMetaDataSafeStatement(sql);
                            proceduresStmt.setString(1, db);
                            if (!StringUtils.isNullOrEmpty(procedureNameFilter)) {
                                proceduresStmt.setString(2, procedureNameFilter);
                            }
                            proceduresRs = proceduresStmt.executeQuery();

                            convertToJdbcProcedureList(false, proceduresRs, procedureRowsToSort);
                        }
                    }

                } finally {
                    SQLException rethrowSqlEx = null;

                    if (proceduresRs != null) {
                        try {
                            proceduresRs.close();
                        } catch (SQLException sqlEx) {
                            rethrowSqlEx = sqlEx;
                        }
                        proceduresRs = null;
                    }

                    if (proceduresStmt != null) {
                        try {
                            proceduresStmt.close();
                        } catch (SQLException sqlEx) {
                            rethrowSqlEx = sqlEx;
                        }
                        proceduresStmt = null;
                    }

                    if (rethrowSqlEx != null) {
                        throw rethrowSqlEx;
                    }
                }
            }

        }.doForAll();

        Collections.sort(procedureRowsToSort);
        for (ComparableWrapper<String, Row> procRow : procedureRowsToSort) {
            rows.add(procRow.getValue());
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public String getProcedureTerm() throws SQLException {
        return "PROCEDURE";
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        List<String> dbList = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? getDatabases(schemaPattern) : new ArrayList<>();

        Field[] fields = new Field[2];
        fields[0] = new Field("", "TABLE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);
        fields[1] = new Field("", "TABLE_CATALOG", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 0);

        ArrayList<Row> rows = new ArrayList<>(dbList.size());
        for (String db : dbList) {
            byte[][] row = new byte[2][];
            row[0] = s2b(db);
            row[1] = s2b("def");
            rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public String getSchemaTerm() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.SCHEMA ? "SCHEMA" : "";
    }

    @Override
    public String getSearchStringEscape() throws SQLException {
        return "\\";
    }

    /**
     * Get a comma separated list of all a database's SQL keywords that are NOT also SQL92/SQL2003 keywords.
     *
     * @return the list
     * @throws SQLException
     *             if a database access error occurs
     */
    @Override
    public String getSQLKeywords() throws SQLException {
        if (mysqlKeywords != null) {
            return mysqlKeywords;
        }

        LOCK.lock();
        try {
            // double check, maybe it's already set
            if (mysqlKeywords != null) {
                return mysqlKeywords;
            }

            Set<String> mysqlKeywordSet = new TreeSet<>();
            StringBuilder mysqlKeywordsBuffer = new StringBuilder();

            Collections.addAll(mysqlKeywordSet, MYSQL_KEYWORDS);
            mysqlKeywordSet.removeAll(SQL2003_KEYWORDS);

            for (String keyword : mysqlKeywordSet) {
                mysqlKeywordsBuffer.append(",").append(keyword);
            }

            mysqlKeywords = mysqlKeywordsBuffer.substring(1);
            return mysqlKeywords;
        } finally {
            LOCK.unlock();
        }
    }

    @Override
    public int getSQLStateType() throws SQLException {
        return sqlStateSQL;
    }

    @Override
    public String getStringFunctions() throws SQLException {
        return "ASCII,BIN,BIT_LENGTH,CHAR,CHARACTER_LENGTH,CHAR_LENGTH,CONCAT,CONCAT_WS,CONV,ELT,EXPORT_SET,FIELD,FIND_IN_SET,HEX,INSERT,"
                + "INSTR,LCASE,LEFT,LENGTH,LOAD_FILE,LOCATE,LOCATE,LOWER,LPAD,LTRIM,MAKE_SET,MATCH,MID,OCT,OCTET_LENGTH,ORD,POSITION,"
                + "QUOTE,REPEAT,REPLACE,REVERSE,RIGHT,RPAD,RTRIM,SOUNDEX,SPACE,STRCMP,SUBSTRING,SUBSTRING,SUBSTRING,SUBSTRING,"
                + "SUBSTRING_INDEX,TRIM,UCASE,UPPER";
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        Field[] fields = new Field[4];
        fields[0] = new Field("", "TABLE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[1] = new Field("", "TABLE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[2] = new Field("", "TABLE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[3] = new Field("", "SUPERTABLE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
        Field[] fields = new Field[6];
        fields[0] = new Field("", "TYPE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[1] = new Field("", "TYPE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[2] = new Field("", "TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[3] = new Field("", "SUPERTYPE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[4] = new Field("", "SUPERTYPE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[5] = new Field("", "SUPERTYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    @Override
    public String getSystemFunctions() throws SQLException {
        return "DATABASE,USER,SYSTEM_USER,SESSION_USER,PASSWORD,ENCRYPT,LAST_INSERT_ID,VERSION";
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        Field[] fields = new Field[7];
        fields[0] = new Field("", "TABLE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "TABLE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 1);
        fields[2] = new Field("", "TABLE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "GRANTOR", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 77);
        fields[4] = new Field("", "GRANTEE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 77);
        fields[5] = new Field("", "PRIVILEGE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 64);
        fields[6] = new Field("", "IS_GRANTABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 3);

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String dbFromTerm = getDatabase(catalog, schemaPattern);
        final String dbFilter = this.pedantic ? dbFromTerm : StringUtils.unQuoteIdentifier(dbFromTerm, this.quotedId);
        final String tableNameFilter = this.pedantic ? tableNamePattern : StringUtils.unQuoteIdentifier(tableNamePattern, this.quotedId);

        StringBuilder query = new StringBuilder("SELECT host,db,table_name,grantor,user,table_priv FROM mysql.tables_priv");

        StringBuilder condition = new StringBuilder();
        if (dbFilter != null) {
            condition.append(dbMapsToSchema ? " db LIKE ?" : " db = ?");
        }
        if (tableNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" table_name LIKE ?");
        }
        if (condition.length() > 0) {
            query.append(" WHERE");
            query.append(condition);
        }

        ResultSet rs = null;
        ArrayList<Row> rows = new ArrayList<>();
        PreparedStatement pStmt = null;

        try {
            pStmt = prepareMetaDataSafeStatement(query.toString());
            int nextId = 1;
            if (dbFilter != null) {
                pStmt.setString(nextId++, storesLowerCaseIdentifiers() ? dbFilter.toLowerCase() : dbFilter);
            }
            if (tableNameFilter != null) {
                pStmt.setString(nextId, storesLowerCaseIdentifiers() ? tableNameFilter.toLowerCase() : tableNameFilter);
            }

            rs = pStmt.executeQuery();

            while (rs.next()) {
                String host = rs.getString(1);
                String db = rs.getString(2);
                String table = rs.getString(3);
                String grantor = rs.getString(4);
                String user = rs.getString(5);
                if (user == null || user.length() == 0) {
                    user = "%";
                }
                StringBuilder fullUser = new StringBuilder(user);
                if (host != null && this.useHostsInPrivileges) {
                    fullUser.append("@");
                    fullUser.append(host);
                }
                String allPrivileges = rs.getString(6);

                if (allPrivileges != null) {
                    allPrivileges = allPrivileges.toUpperCase(Locale.ENGLISH);
                    StringTokenizer st = new StringTokenizer(allPrivileges, ",");
                    while (st.hasMoreTokens()) {
                        String privilege = st.nextToken().trim();

                        // Loop through every column in the table
                        ResultSet columnRs = null;
                        try {
                            columnRs = getColumns(catalog, schemaPattern, StringUtils.quoteIdentifier(table, this.quotedId, !this.pedantic), null);

                            while (columnRs.next()) {
                                byte[][] row = new byte[8][];
                                row[0] = dbMapsToSchema ? s2b("def") : s2b(db);  // PKTABLE_CAT
                                row[1] = dbMapsToSchema ? s2b(db) : null;        // PKTABLE_SCHEM
                                row[2] = s2b(table);
                                row[3] = grantor != null ? s2b(grantor) : null;
                                row[4] = s2b(fullUser.toString());
                                row[5] = s2b(privilege);
                                row[6] = null;
                                rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                            }
                        } finally {
                            if (columnRs != null) {
                                try {
                                    columnRs.close();
                                } catch (Exception ex) {
                                }
                                columnRs = null;
                            }
                        }
                    }
                }
            }
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (Exception ex) {
                }
                rs = null;
            }
            if (pStmt != null) {
                try {
                    pStmt.close();
                } catch (Exception ex) {
                }
                pStmt = null;
            }
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, final String[] types) throws SQLException {
        final SortedMap<TableMetaDataKey, Row> sortedRows = new TreeMap<>();
        final ArrayList<Row> rows = new ArrayList<>();
        final Statement stmt = this.conn.getMetadataSafeStatement();

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
        final String dbFilter = getDatabase(catalog, schemaPattern);
        final String tableNameFilter = this.pedantic ? tableNamePattern : StringUtils.unQuoteIdentifier(tableNamePattern, this.quotedId);

        try {
            new IterateBlock<String>(dbMapsToSchema ? getSchemaPatternIterator(dbFilter) : getDatabaseIterator(dbFilter)) {

                @Override
                void forEach(String db) throws SQLException {
                    boolean operatingOnSystemDB = "information_schema".equalsIgnoreCase(db) || "mysql".equalsIgnoreCase(db)
                            || "performance_schema".equalsIgnoreCase(db) || "sys".equalsIgnoreCase(db);

                    ResultSet rs = null;
                    try {
                        try {
                            StringBuilder query = new StringBuilder("SHOW FULL TABLES FROM ");
                            query.append(StringUtils.quoteIdentifier(db, DatabaseMetaData.this.quotedId, true));
                            if (tableNameFilter != null) {
                                query.append(" LIKE ");
                                query.append(StringUtils.quoteIdentifier(tableNameFilter, "'", true));
                            }
                            rs = stmt.executeQuery(query.toString());
                        } catch (SQLException sqlEx) {
                            if (MysqlErrorNumbers.SQLSTATE_MYSQL_COMMUNICATION_LINK_FAILURE.equals(sqlEx.getSQLState())) {
                                throw sqlEx;
                            }
                            return;
                        }

                        boolean shouldReportTables = false;
                        boolean shouldReportViews = false;
                        boolean shouldReportSystemTables = false;
                        boolean shouldReportSystemViews = false;
                        boolean shouldReportLocalTemporaries = false;

                        if (types == null || types.length == 0) {
                            shouldReportTables = true;
                            shouldReportViews = true;
                            shouldReportSystemTables = true;
                            shouldReportSystemViews = true;
                            shouldReportLocalTemporaries = true;
                        } else {
                            for (int i = 0; i < types.length; i++) {
                                if (TableType.TABLE.equalsTo(types[i])) {
                                    shouldReportTables = true;

                                } else if (TableType.VIEW.equalsTo(types[i])) {
                                    shouldReportViews = true;

                                } else if (TableType.SYSTEM_TABLE.equalsTo(types[i])) {
                                    shouldReportSystemTables = true;

                                } else if (TableType.SYSTEM_VIEW.equalsTo(types[i])) {
                                    shouldReportSystemViews = true;

                                } else if (TableType.LOCAL_TEMPORARY.equalsTo(types[i])) {
                                    shouldReportLocalTemporaries = true;
                                }
                            }
                        }

                        int typeColumnIndex = 0;
                        boolean hasTableTypes = false;

                        try {
                            // Both column names have been in use in the source tree so far....
                            typeColumnIndex = rs.findColumn("table_type");
                            hasTableTypes = true;
                        } catch (SQLException sqlEx) {
                            // We should probably check SQLState here, but that can change depending on the server version and user properties, however,
                            // we'll get a 'true' SQLException when we actually try to find the 'Type' column
                            try {
                                typeColumnIndex = rs.findColumn("Type");
                                hasTableTypes = true;
                            } catch (SQLException sqlEx2) {
                                hasTableTypes = false;
                            }
                        }

                        while (rs.next()) {
                            byte[][] row = new byte[10][];
                            row[0] = dbMapsToSchema ? s2b("def") : s2b(db);// TABLE_CAT
                            row[1] = dbMapsToSchema ? s2b(db) : null;      // TABLE_SCHEM
                            row[2] = rs.getBytes(1);
                            row[4] = new byte[0];
                            row[5] = null;
                            row[6] = null;
                            row[7] = null;
                            row[8] = null;
                            row[9] = null;

                            if (hasTableTypes) {
                                String tableType = rs.getString(typeColumnIndex);

                                switch (TableType.getTableTypeCompliantWith(tableType)) {
                                    case TABLE:
                                        boolean reportTable = false;
                                        TableMetaDataKey tablesKey = null;

                                        if (operatingOnSystemDB && shouldReportSystemTables) {
                                            row[3] = TableType.SYSTEM_TABLE.asBytes();
                                            tablesKey = new TableMetaDataKey(TableType.SYSTEM_TABLE.getName(), db, null, rs.getString(1));
                                            reportTable = true;

                                        } else if (!operatingOnSystemDB && shouldReportTables) {
                                            row[3] = TableType.TABLE.asBytes();
                                            tablesKey = new TableMetaDataKey(TableType.TABLE.getName(), db, null, rs.getString(1));
                                            reportTable = true;
                                        }

                                        if (reportTable) {
                                            sortedRows.put(tablesKey, new ByteArrayRow(row, getExceptionInterceptor()));
                                        }
                                        break;

                                    case VIEW:
                                        if (shouldReportViews) {
                                            row[3] = TableType.VIEW.asBytes();
                                            sortedRows.put(new TableMetaDataKey(TableType.VIEW.getName(), db, null, rs.getString(1)),
                                                    new ByteArrayRow(row, getExceptionInterceptor()));
                                        }
                                        break;

                                    case SYSTEM_TABLE:
                                        if (shouldReportSystemTables) {
                                            row[3] = TableType.SYSTEM_TABLE.asBytes();
                                            sortedRows.put(new TableMetaDataKey(TableType.SYSTEM_TABLE.getName(), db, null, rs.getString(1)),
                                                    new ByteArrayRow(row, getExceptionInterceptor()));
                                        }
                                        break;

                                    case SYSTEM_VIEW:
                                        if (shouldReportSystemViews) {
                                            row[3] = TableType.SYSTEM_VIEW.asBytes();
                                            sortedRows.put(new TableMetaDataKey(TableType.SYSTEM_VIEW.getName(), db, null, rs.getString(1)),
                                                    new ByteArrayRow(row, getExceptionInterceptor()));
                                        }
                                        break;

                                    case LOCAL_TEMPORARY:
                                        if (shouldReportLocalTemporaries) {
                                            row[3] = TableType.LOCAL_TEMPORARY.asBytes();
                                            sortedRows.put(new TableMetaDataKey(TableType.LOCAL_TEMPORARY.getName(), db, null, rs.getString(1)),
                                                    new ByteArrayRow(row, getExceptionInterceptor()));
                                        }
                                        break;

                                    default:
                                        row[3] = TableType.TABLE.asBytes();
                                        sortedRows.put(new TableMetaDataKey(TableType.TABLE.getName(), db, null, rs.getString(1)),
                                                new ByteArrayRow(row, getExceptionInterceptor()));
                                        break;
                                }
                            } else // TODO: Check if this branch is needed for 5.7 server (maybe refactor hasTableTypes)
                            if (shouldReportTables) {
                                // Pre-MySQL-5.0.1, tables only
                                row[3] = TableType.TABLE.asBytes();
                                sortedRows.put(new TableMetaDataKey(TableType.TABLE.getName(), db, null, rs.getString(1)),
                                        new ByteArrayRow(row, getExceptionInterceptor()));
                            }
                        }

                    } finally {
                        if (rs != null) {
                            try {
                                rs.close();
                            } catch (Exception ex) {
                            }
                            rs = null;
                        }
                    }
                }

            }.doForAll();
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }

        rows.addAll(sortedRows.values());
        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, createTablesFields()));
    }

    protected ColumnDefinition createTablesFields() {
        Field[] fields = new Field[10];
        fields[0] = new Field("", "TABLE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 255);
        fields[1] = new Field("", "TABLE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 0);
        fields[2] = new Field("", "TABLE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 255);
        fields[3] = new Field("", "TABLE_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 5);
        fields[4] = new Field("", "REMARKS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 0);
        fields[5] = new Field("", "TYPE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 0);
        fields[6] = new Field("", "TYPE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 0);
        fields[7] = new Field("", "TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 0);
        fields[8] = new Field("", "SELF_REFERENCING_COL_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 0);
        fields[9] = new Field("", "REF_GENERATION", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 0);
        return new DefaultColumnDefinition(fields);
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        ArrayList<Row> rows = new ArrayList<>();
        Field[] fields = new Field[] { new Field("", "TABLE_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 256) };

        rows.add(new ByteArrayRow(new byte[][] { TableType.LOCAL_TEMPORARY.asBytes() }, getExceptionInterceptor()));
        rows.add(new ByteArrayRow(new byte[][] { TableType.SYSTEM_TABLE.asBytes() }, getExceptionInterceptor()));
        rows.add(new ByteArrayRow(new byte[][] { TableType.SYSTEM_VIEW.asBytes() }, getExceptionInterceptor()));
        rows.add(new ByteArrayRow(new byte[][] { TableType.TABLE.asBytes() }, getExceptionInterceptor()));
        rows.add(new ByteArrayRow(new byte[][] { TableType.VIEW.asBytes() }, getExceptionInterceptor()));

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public String getTimeDateFunctions() throws SQLException {
        return "DAYOFWEEK,WEEKDAY,DAYOFMONTH,DAYOFYEAR,MONTH,DAYNAME,MONTHNAME,QUARTER,WEEK,YEAR,HOUR,MINUTE,SECOND,PERIOD_ADD,"
                + "PERIOD_DIFF,TO_DAYS,FROM_DAYS,DATE_FORMAT,TIME_FORMAT,CURDATE,CURRENT_DATE,CURTIME,CURRENT_TIME,NOW,SYSDATE,"
                + "CURRENT_TIMESTAMP,UNIX_TIMESTAMP,FROM_UNIXTIME,SEC_TO_TIME,TIME_TO_SEC";
    }

    /**
     *
     * @param mysqlTypeName
     *            we use a string name here to allow aliases for the same MysqlType to be listed too
     * @return bytes
     * @throws SQLException
     *             if a conversion error occurs
     */
    private byte[][] getTypeInfo(String mysqlTypeName) throws SQLException {
        MysqlType mt = MysqlType.getByName(mysqlTypeName);
        byte[][] row = new byte[18][];

        row[0] = s2b(mysqlTypeName);                                                     // Type name
        row[1] = Integer.toString(mt == MysqlType.YEAR && !DatabaseMetaData.this.yearIsDateType ? Types.SMALLINT : mt.getJdbcType()).getBytes();                          // JDBC Data type
        // JDBC spec reserved only 'int' type for precision, thus we need to cut longer values
        row[2] = Integer.toString(mt.getPrecision() > Integer.MAX_VALUE ? Integer.MAX_VALUE : mt.getPrecision().intValue()).getBytes(); // Precision
        switch (mt) {
            case TINYBLOB:
            case BLOB:
            case MEDIUMBLOB:
            case LONGBLOB:
            case TINYTEXT:
            case TEXT:
            case MEDIUMTEXT:
            case LONGTEXT:
            case JSON:
            case BINARY:
            case VARBINARY:
            case CHAR:
            case VARCHAR:
            case ENUM:
            case SET:
            case DATE:
            case TIME:
            case DATETIME:
            case TIMESTAMP:
            case GEOMETRY:
            case VECTOR:
            case UNKNOWN:
                row[3] = s2b("'");                                                       // Literal Prefix
                row[4] = s2b("'");                                                       // Literal Suffix
                break;
            default:
                row[3] = s2b("");                                                        // Literal Prefix
                row[4] = s2b("");                                                        // Literal Suffix
        }
        row[5] = s2b(mt.getCreateParams());                                              // Create Params
        row[6] = Integer.toString(typeNullable).getBytes();                              // Nullable
        row[7] = s2b("true");                                                            // Case Sensitive
        row[8] = Integer.toString(typeSearchable).getBytes();                            // Searchable
        row[9] = s2b(mt.isAllowed(MysqlType.FIELD_FLAG_UNSIGNED) ? "true" : "false");    // Unsignable
        row[10] = s2b("false");                                                          // Fixed Prec Scale
        switch (mt) {
            case BIGINT:
            case BIGINT_UNSIGNED:
            case BOOLEAN:
            case INT:
            case INT_UNSIGNED:
            case MEDIUMINT:
            case MEDIUMINT_UNSIGNED:
            case SMALLINT:
            case SMALLINT_UNSIGNED:
            case TINYINT:
            case TINYINT_UNSIGNED:
                row[11] = s2b("true");                                                   // Auto Increment
                break;
            case DOUBLE:
            case DOUBLE_UNSIGNED:
            case FLOAT:
            case FLOAT_UNSIGNED:
                boolean supportsAutoIncrement = !this.session.versionMeetsMinimum(8, 4, 0);
                row[11] = supportsAutoIncrement ? s2b("true") : s2b("false");            // Auto Increment
                break;
            default:
                row[11] = s2b("false");                                                  // Auto Increment
                break;
        }
        row[12] = s2b(mt.getName());                                                     // Locale Type Name
        switch (mt) {
            case DECIMAL: // TODO is it right? DECIMAL isn't a floating-point number...
            case DECIMAL_UNSIGNED:
            case DOUBLE:
            case DOUBLE_UNSIGNED:
                row[13] = s2b("-308");                                                   // Minimum Scale
                row[14] = s2b("308");                                                    // Maximum Scale
                break;
            case FLOAT:
            case FLOAT_UNSIGNED:
                row[13] = s2b("-38");                                                    // Minimum Scale
                row[14] = s2b("38");                                                     // Maximum Scale
                break;
            default:
                row[13] = s2b("0");                                                      // Minimum Scale
                row[14] = s2b("0");                                                      // Maximum Scale
        }
        row[15] = s2b("0");                                                              // SQL Data Type (not used)
        row[16] = s2b("0");                                                              // SQL DATETIME SUB (not used)
        row[17] = s2b("10");                                                             // NUM_PREC_RADIX (2 or 10)

        return row;
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        Field[] fields = new Field[18];
        fields[0] = new Field("", "TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[1] = new Field("", "DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 5);
        fields[2] = new Field("", "PRECISION", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[3] = new Field("", "LITERAL_PREFIX", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 4);
        fields[4] = new Field("", "LITERAL_SUFFIX", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 4);
        fields[5] = new Field("", "CREATE_PARAMS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[6] = new Field("", "NULLABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 5);
        fields[7] = new Field("", "CASE_SENSITIVE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.BOOLEAN, 3);
        fields[8] = new Field("", "SEARCHABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 3);
        fields[9] = new Field("", "UNSIGNED_ATTRIBUTE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.BOOLEAN, 3);
        fields[10] = new Field("", "FIXED_PREC_SCALE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.BOOLEAN, 3);
        fields[11] = new Field("", "AUTO_INCREMENT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.BOOLEAN, 3);
        fields[12] = new Field("", "LOCAL_TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[13] = new Field("", "MINIMUM_SCALE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 5);
        fields[14] = new Field("", "MAXIMUM_SCALE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 5);
        fields[15] = new Field("", "SQL_DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[16] = new Field("", "SQL_DATETIME_SUB", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[17] = new Field("", "NUM_PREC_RADIX", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);

        ArrayList<Row> rows = new ArrayList<>();

        // Ordered by DATA_TYPE and then by how closely the data type maps to the corresponding JDBC SQL type.
        // java.sql.Types.BIT = -7
        rows.add(new ByteArrayRow(getTypeInfo("BIT"), getExceptionInterceptor()));
        // java.sql.Types.TINYINT = -6
        rows.add(new ByteArrayRow(getTypeInfo("TINYINT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("TINYINT UNSIGNED"), getExceptionInterceptor()));
        // java.sql.Types.BIGINT = -5
        rows.add(new ByteArrayRow(getTypeInfo("BIGINT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("BIGINT UNSIGNED"), getExceptionInterceptor()));
        // java.sql.Types.LONGVARBINARY = -4
        rows.add(new ByteArrayRow(getTypeInfo("LONG VARBINARY"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("MEDIUMBLOB"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("LONGBLOB"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("BLOB"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("VECTOR"), getExceptionInterceptor()));
        // java.sql.Types.VARBINARY = -3
        rows.add(new ByteArrayRow(getTypeInfo("VARBINARY"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("TINYBLOB"), getExceptionInterceptor()));
        // java.sql.Types.BINARY = -2
        rows.add(new ByteArrayRow(getTypeInfo("BINARY"), getExceptionInterceptor()));
        // java.sql.Types.LONGVARCHAR = -1
        rows.add(new ByteArrayRow(getTypeInfo("LONG VARCHAR"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("MEDIUMTEXT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("LONGTEXT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("TEXT"), getExceptionInterceptor()));
        // java.sql.Types.CHAR = 1
        rows.add(new ByteArrayRow(getTypeInfo("CHAR"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("ENUM"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("SET"), getExceptionInterceptor()));
        // java.sql.Types.DECIMAL = 3
        rows.add(new ByteArrayRow(getTypeInfo("DECIMAL"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("NUMERIC"), getExceptionInterceptor()));
        // java.sql.Types.INTEGER = 4
        rows.add(new ByteArrayRow(getTypeInfo("INTEGER"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("INT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("MEDIUMINT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("INTEGER UNSIGNED"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("INT UNSIGNED"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("MEDIUMINT UNSIGNED"), getExceptionInterceptor()));
        // java.sql.Types.SMALLINT = 5
        rows.add(new ByteArrayRow(getTypeInfo("SMALLINT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("SMALLINT UNSIGNED"), getExceptionInterceptor()));
        if (!this.yearIsDateType) {
            rows.add(new ByteArrayRow(getTypeInfo("YEAR"), getExceptionInterceptor()));
        }
        // java.sql.Types.REAL = 7
        rows.add(new ByteArrayRow(getTypeInfo("FLOAT"), getExceptionInterceptor()));
        // java.sql.Types.DOUBLE = 8
        rows.add(new ByteArrayRow(getTypeInfo("DOUBLE"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("DOUBLE PRECISION"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("REAL"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("DOUBLE UNSIGNED"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("DOUBLE PRECISION UNSIGNED"), getExceptionInterceptor()));
        // java.sql.Types.VARCHAR = 12
        rows.add(new ByteArrayRow(getTypeInfo("VARCHAR"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("TINYTEXT"), getExceptionInterceptor()));
        // java.sql.Types.BOOLEAN = 16
        rows.add(new ByteArrayRow(getTypeInfo("BOOL"), getExceptionInterceptor()));
        // java.sql.Types.DATE = 91
        rows.add(new ByteArrayRow(getTypeInfo("DATE"), getExceptionInterceptor()));
        if (this.yearIsDateType) {
            rows.add(new ByteArrayRow(getTypeInfo("YEAR"), getExceptionInterceptor()));
        }
        // java.sql.Types.TIME = 92
        rows.add(new ByteArrayRow(getTypeInfo("TIME"), getExceptionInterceptor()));
        // java.sql.Types.TIMESTAMP = 93
        rows.add(new ByteArrayRow(getTypeInfo("DATETIME"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("TIMESTAMP"), getExceptionInterceptor()));

        // TODO add missed types (aliases)

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws SQLException {
        Field[] fields = new Field[7];
        fields[0] = new Field("", "TYPE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 32);
        fields[1] = new Field("", "TYPE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 32);
        fields[2] = new Field("", "TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 32);
        fields[3] = new Field("", "CLASS_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 32);
        fields[4] = new Field("", "DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[5] = new Field("", "REMARKS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 32);
        fields[6] = new Field("", "BASE_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 10);

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    @Override
    public String getURL() throws SQLException {
        return this.conn.getURL();
    }

    @Override
    public String getUserName() throws SQLException {
        if (this.useHostsInPrivileges) {
            Statement stmt = null;
            ResultSet rs = null;

            try {
                stmt = this.conn.getMetadataSafeStatement();
                rs = stmt.executeQuery("SELECT USER()");
                rs.next();
                return rs.getString(1);
            } finally {
                if (rs != null) {
                    try {
                        rs.close();
                    } catch (Exception ex) {
                        AssertionFailedException.shouldNotHappen(ex);
                    }
                    rs = null;
                }
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (Exception ex) {
                        AssertionFailedException.shouldNotHappen(ex);
                    }
                    stmt = null;
                }
            }
        }
        return this.conn.getUser();
    }

    protected Field[] getVersionColumnsFields() {
        Field[] fields = new Field[8];
        fields[0] = new Field("", "SCOPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 5);
        fields[1] = new Field("", "COLUMN_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 32);
        fields[2] = new Field("", "DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 5);
        fields[3] = new Field("", "TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 16);
        fields[4] = new Field("", "COLUMN_SIZE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 16);
        fields[5] = new Field("", "BUFFER_LENGTH", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 16);
        fields[6] = new Field("", "DECIMAL_DIGITS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 16);
        fields[7] = new Field("", "PSEUDO_COLUMN", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 5);
        return fields;
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, final String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFilter = getDatabase(catalog, schema);
        final String tableFilter = this.pedantic ? table : StringUtils.unQuoteIdentifier(table, this.quotedId);

        final ArrayList<Row> rows = new ArrayList<>();
        final Statement stmt = this.conn.getMetadataSafeStatement();

        try {
            new IterateBlock<String>(getDatabaseIterator(dbFilter)) {

                @Override
                void forEach(String db) throws SQLException {
                    ResultSet rs = null;
                    try {
                        StringBuilder query = new StringBuilder("SHOW COLUMNS FROM ");
                        query.append(StringUtils.quoteIdentifier(tableFilter, DatabaseMetaData.this.quotedId, true));
                        query.append(" FROM ");
                        query.append(StringUtils.quoteIdentifier(db, DatabaseMetaData.this.quotedId, true));
                        query.append(" WHERE Extra LIKE '%on update CURRENT_TIMESTAMP%'");

                        try {
                            rs = stmt.executeQuery(query.toString());
                        } catch (SQLException e) {
                            String sqlState = e.getSQLState(); // Ignore exception if SQLState is 42S02 or 42000 - table/database doesn't exist.
                            int errorCode = e.getErrorCode(); // Ignore exception if ErrorCode is 1146, 1109, or 1149 - table/database doesn't exist.

                            if (!("42S02".equals(sqlState)
                                    && (errorCode == MysqlErrorNumbers.ER_NO_SUCH_TABLE || errorCode == MysqlErrorNumbers.ER_UNKNOWN_TABLE)
                                    || "42000".equals(sqlState) && errorCode == MysqlErrorNumbers.ER_BAD_DB_ERROR)) {
                                throw e;
                            }
                        }

                        while (rs != null && rs.next()) {
                            TypeDescriptor typeDesc = new TypeDescriptor(rs.getString("Type"), rs.getString("Null"));
                            byte[][] row = new byte[8][];
                            row[0] = null;                                                                           // SCOPE is not used
                            row[1] = rs.getBytes("Field");                                                           // COLUMN_NAME
                            row[2] = Short.toString(typeDesc.mysqlType == MysqlType.YEAR && !DatabaseMetaData.this.yearIsDateType ? Types.SMALLINT
                                    : (short) typeDesc.mysqlType.getJdbcType()).getBytes();                          // DATA_TYPE (jdbc)
                            row[3] = s2b(typeDesc.mysqlType.getName());                                              // TYPE_NAME
                            row[4] = typeDesc.columnSize == null ? null : s2b(typeDesc.columnSize.toString());       // COLUMN_SIZE
                            row[5] = s2b(Integer.toString(typeDesc.bufferLength));                                   // BUFFER_LENGTH
                            row[6] = typeDesc.decimalDigits == null ? null : s2b(typeDesc.decimalDigits.toString()); // DECIMAL_DIGITS
                            row[7] = Integer.toString(versionColumnNotPseudo).getBytes();                            // PSEUDO_COLUMN
                            rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                        }
                    } catch (SQLException sqlEx) {
                        if (!MysqlErrorNumbers.SQLSTATE_MYSQL_BASE_TABLE_OR_VIEW_NOT_FOUND.equals(sqlEx.getSQLState())) {
                            throw sqlEx;
                        }
                    } finally {
                        if (rs != null) {
                            try {
                                rs.close();
                            } catch (Exception ex) {
                            }
                            rs = null;
                        }
                    }
                }

            }.doForAll();
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(getVersionColumnsFields())));
    }

    @Override
    public boolean insertsAreDetected(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean isCatalogAtStart() throws SQLException {
        return true; // There is no similar method for SCHEMA
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() throws SQLException {
        return !this.conn.getPropertySet().getBooleanProperty(PropertyKey.emulateLocators).getValue();
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() throws SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        return !nullsAreSortedHigh();
    }

    @Override
    public boolean othersDeletesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) throws SQLException {
        return false;
    }

    /**
     * Populates the resultRows list with the imported or exported keys of given table based on the keysComment from the 'show table status' SQL command.
     * KeysComment is that part of the comment field that follows the "InnoDB free ...;" prefix.
     *
     * @param parentDatabase
     *            the database of the parent table
     * @param parentTable
     *            the parent key table name
     * @param foreignDatabase
     *            the database of the foreign table
     * @param foreignTable
     *            the foreign key table name
     * @param keys
     *            the comment from 'show table status'
     * @param forExport
     *            export or import keys
     * @param resultRows
     *            the rows to add results to
     * @throws SQLException
     *             if an error occurs
     */
    void populateKeyResults(String parentDatabase, String parentTable, String foreignDatabase, String foreignTable, String keys, boolean forExport,
            List<Row> resultRows) throws SQLException {
        ReferencingAndReferencedColumns parsedInfo = parseTableStatusIntoReferencingAndReferencedColumns(keys);

        if (forExport) {
            if (parentDatabase == null && this.nullDatabaseMeansCurrent.getValue()) {
                parentDatabase = this.database;
            }
            if (!parsedInfo.referencedTable.contentEquals(parentTable)
                    || parentDatabase != null && !parsedInfo.referencedDatabase.contentEquals(parentDatabase)) {
                return;
            }
        }

        if (parsedInfo.referencingColumnsList.size() != parsedInfo.referencedColumnsList.size()) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.12"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                    getExceptionInterceptor());
        }

        final boolean dbMapsToSchema = this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;

        int keySeq = 1;
        Iterator<String> referencingColumns = parsedInfo.referencingColumnsList.iterator();
        Iterator<String> referencedColumns = parsedInfo.referencedColumnsList.iterator();
        while (referencingColumns.hasNext()) {
            byte[][] row = new byte[14][];
            row[0] = dbMapsToSchema ? s2b("def") : s2b(parsedInfo.referencedDatabase); // PKTABLE_CAT
            row[1] = dbMapsToSchema ? s2b(parsedInfo.referencedDatabase) : null;       // PKTABLE_SCHEM
            row[2] = s2b(parsedInfo.referencedTable);                                  // PKTABLE_NAME
            String referencedColumn = StringUtils.unQuoteIdentifier(referencedColumns.next(), this.quotedId);
            row[3] = s2b(referencedColumn);                                            // PKCOLUMN_NAME
            row[4] = dbMapsToSchema ? s2b("def") : s2b(foreignDatabase);               // FKTABLE_CAT
            row[5] = dbMapsToSchema ? s2b(foreignDatabase) : null;                     // FKTABLE_SCHEM
            row[6] = s2b(foreignTable);                                                // FKTABLE_NAME
            String referencingColumn = StringUtils.unQuoteIdentifier(referencingColumns.next(), this.quotedId);
            row[7] = s2b(referencingColumn);                                           // FKCOLUMN_NAME
            row[8] = s2b(Integer.toString(keySeq++));                                  // KEY_SEQ
            int[] actions = getForeignKeyActions(keys);
            row[9] = s2b(Integer.toString(actions[1]));                                // UPDATE_RULE
            row[10] = s2b(Integer.toString(actions[0]));                               // DELETE_RULE
            row[11] = s2b(parsedInfo.constraintName);                                  // FK_NAME
            row[12] = null;                                                            // PK_NAME, not available from show table status
            row[13] = s2b(Integer.toString(importedKeyNotDeferrable));                 // DEFERRABILITY
            resultRows.add(new ByteArrayRow(row, getExceptionInterceptor()));
        }
    }

    /**
     * Parses a keysComment section from the 'show table status' SQL command.
     * KeysComment is that part of the comment field that follows the "InnoDB free ...;" prefix. (*)
     *
     * keys will equal something like this: (parent_service_id child_service_id) REFER ds/subservices(parent_service_id child_service_id).
     *
     * simple-columned keys e.g.: (key REFER employees/employee(id)
     * multi-columned keys e.g.: (key1 key2) REFER employees/employee(id1 id2)
     *
     * parse of the string into three phases:
     * 1: parse the opening parentheses to determine how many results there will be.
     * 2: read in the schema name/table name.
     * 3: parse the closing parentheses.
     *
     * (*) This is no longer the case. Instead, foreign key information is currently retrieved from SHOW CREATE TABLE and reassembled using this structure. See
     * also {@link #extractForeignKeyForTable(ArrayList, ResultSet, String)}.
     *
     * @param keysComment
     *            the comment from 'show table status'
     * @return
     *         a {@link ReferencingAndReferencedColumns} instance containing the parsed information
     * @throws SQLException
     *             if an error occurs
     */
    ReferencingAndReferencedColumns parseTableStatusIntoReferencingAndReferencedColumns(String keysComment) throws SQLException {
        String columnsDelimitter = ",";
        int indexOfOpenParenLocalColumns = StringUtils.indexOfIgnoreCase(0, keysComment, "(", this.quotedId, this.quotedId,
                SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
        if (indexOfOpenParenLocalColumns == -1) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.14"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                    getExceptionInterceptor());
        }
        String constraintName = StringUtils.unQuoteIdentifier(keysComment.substring(0, indexOfOpenParenLocalColumns).trim(), this.quotedId);
        keysComment = keysComment.substring(indexOfOpenParenLocalColumns, keysComment.length());
        String keysCommentTrimmed = keysComment.trim();

        int indexOfCloseParenLocalColumns = StringUtils.indexOfIgnoreCase(0, keysCommentTrimmed, ")", this.quotedId, this.quotedId,
                SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
        if (indexOfCloseParenLocalColumns == -1) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.15"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                    getExceptionInterceptor());
        }
        String localColumnNames = keysCommentTrimmed.substring(1, indexOfCloseParenLocalColumns);

        int indexOfRefer = StringUtils.indexOfIgnoreCase(0, keysCommentTrimmed, "REFER ", this.quotedId, this.quotedId, SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
        if (indexOfRefer == -1) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.16"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                    getExceptionInterceptor());
        }
        indexOfRefer += "REFER ".length();
        int indexOfOpenParenReferCol = StringUtils.indexOfIgnoreCase(indexOfRefer, keysCommentTrimmed, "(", this.quotedId, this.quotedId,
                SearchMode.__MRK_COM_MYM_HNT_WS);
        if (indexOfOpenParenReferCol == -1) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.17"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                    getExceptionInterceptor());
        }
        String referDbTable = keysCommentTrimmed.substring(indexOfRefer, indexOfOpenParenReferCol);

        int indexOfSlash = StringUtils.indexOfIgnoreCase(0, referDbTable, "/", this.quotedId, this.quotedId, SearchMode.__MRK_COM_MYM_HNT_WS);
        if (indexOfSlash == -1) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.18"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                    getExceptionInterceptor());
        }
        String referDb = StringUtils.unQuoteIdentifier(referDbTable.substring(0, indexOfSlash), this.quotedId);
        String referTable = StringUtils.unQuoteIdentifier(referDbTable.substring(indexOfSlash + 1), this.quotedId);

        int indexOfCloseParenRefer = StringUtils.indexOfIgnoreCase(indexOfOpenParenReferCol, keysCommentTrimmed, ")", this.quotedId, this.quotedId,
                SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
        if (indexOfCloseParenRefer == -1) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.19"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                    getExceptionInterceptor());
        }
        String referColumnNamesString = keysCommentTrimmed.substring(indexOfOpenParenReferCol + 1, indexOfCloseParenRefer);

        List<String> referColumnsList = StringUtils.split(referColumnNamesString, columnsDelimitter, this.quotedId, this.quotedId, false);
        List<String> localColumnsList = StringUtils.split(localColumnNames, columnsDelimitter, this.quotedId, this.quotedId, false);

        return new ReferencingAndReferencedColumns(localColumnsList, referColumnsList, constraintName, referDb, referTable);
    }

    /**
     * Returns the DELETE and UPDATE foreign key actions from the given 'SHOW TABLE STATUS' string, with the DELETE action being the first item in the array,
     * and the UPDATE action being the second. (*)
     *
     * (*) This is no longer the case. Instead, foreign key information is currently retrieved from SHOW CREATE TABLE and reassembled using this structure.
     * See also {@link #extractForeignKeyForTable(ArrayList, ResultSet, String)}.
     *
     * @param commentString
     *            the comment from 'SHOW TABLE STATUS'
     * @return int[] [0] = delete action, [1] = update action
     */
    protected int[] getForeignKeyActions(String commentString) {
        int[] actions = new int[] { importedKeyRestrict, importedKeyRestrict };

        int lastParenIndex = commentString.lastIndexOf(")");
        if (lastParenIndex != commentString.length() - 1) {
            String cascadeOptions = commentString.substring(lastParenIndex + 1).trim().toUpperCase(Locale.ENGLISH);
            actions[0] = getCascadeDeleteOption(cascadeOptions);
            actions[1] = getCascadeUpdateOption(cascadeOptions);
        }

        return actions;
    }

    /**
     * Normalizes the entity name considering how identifiers are stored in relation to their case.
     *
     * @param entity
     *            the entity name to normalize
     * @return a case-normalized version of the specified entity name
     */
    protected String normalizeCase(String entity) {
        if (entity == null) {
            return null;
        }
        try {
            return storesLowerCaseIdentifiers() ? entity.toLowerCase() : entity;
        } catch (SQLException e) {
            return entity;
        }
    }

    /**
     * Converts the given string to bytes, using the connection's character
     * encoding, or if not available, the JVM default encoding.
     *
     * @param s
     *            string
     * @return bytes
     * @throws SQLException
     *             if a conversion error occurs
     */
    protected byte[] s2b(String s) throws SQLException {
        if (s == null) {
            return null;
        }

        try {
            return StringUtils.getBytes(s, this.conn.getCharacterSetMetadata());
        } catch (CJException e) {
            throw SQLExceptionsMapping.translateException(e, getExceptionInterceptor());
        }
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws SQLException {
        return this.conn.storesLowerCaseTableName();
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
        return this.conn.storesLowerCaseTableName();
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws SQLException {
        return !this.conn.storesLowerCaseTableName();
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
        return !this.conn.storesLowerCaseTableName();
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException {
        // NOTE: All JDBC compliant drivers must return true.
        return true;
    }

    @Override
    public boolean supportsANSI92FullSQL() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.CATALOG;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.CATALOG;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.CATALOG;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.CATALOG;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.CATALOG;
    }

    @Override
    public boolean supportsColumnAliasing() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean supportsConvert() throws SQLException {
        // TODO MySQL has a CONVERT() function, is it irrelevant here?
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) throws SQLException {
        return MysqlType.supportsConvert(fromType, toType);
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        if (!this.conn.getPropertySet().getBooleanProperty(PropertyKey.overrideSupportsIntegrityEnhancementFacility).getValue()) {
            return false;
        }
        return true;
    }

    @Override
    public boolean supportsLikeEscapeClause() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException {
        // NOTE: All JDBC compliant drivers must return true.
        return true;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws SQLException {
        return !this.conn.lowerCaseTableNames();
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return !this.conn.lowerCaseTableNames();
    }

    @Override
    public boolean supportsMultipleOpenResults() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMultipleResultSets() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMultipleTransactions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsNamedParameters() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsNonNullableColumns() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOrderByUnrelated() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsPositionedDelete() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
        if ((type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE)
                && (concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE)) {
            return true;
        } else if (type == ResultSet.TYPE_SCROLL_SENSITIVE) {
            return false;
        }
        throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.20"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                getExceptionInterceptor());
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) throws SQLException {
        return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public boolean supportsResultSetType(int type) throws SQLException {
        return type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE;
    }

    @Override
    public boolean supportsSavepoints() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException {
        return this.databaseTerm.getValue() == DatabaseTerm.SCHEMA;
    }

    @Override
    public boolean supportsSelectForUpdate() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsStatementPooling() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean supportsTableCorrelationNames() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
        switch (level) {
            case Connection.TRANSACTION_READ_COMMITTED:
            case Connection.TRANSACTION_READ_UNCOMMITTED:
            case Connection.TRANSACTION_REPEATABLE_READ:
            case Connection.TRANSACTION_SERIALIZABLE:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean supportsTransactions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsUnion() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean supportsUnionAll() throws SQLException {
        // NOTE: A JDBC compliant driver always returns true.
        return true;
    }

    @Override
    public boolean updatesAreDetected(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() throws SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFiles() throws SQLException {
        return false;
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        // We don't have any built-ins, we actually support whatever the client wants to provide, however we don't have a way to express this with the interface
        // given
        Field[] fields = new Field[4];
        fields[0] = new Field("", "NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 255);
        fields[1] = new Field("", "MAX_LEN", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 10);
        fields[2] = new Field("", "DEFAULT_VALUE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 255);
        fields[3] = new Field("", "DESCRIPTION", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 255);

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException {
        return getProcedureOrFunctionColumns(createFunctionColumnsFields(), catalog, schemaPattern, functionNamePattern, columnNamePattern, false, true);
    }

    protected Field[] createFunctionColumnsFields() {
        Field[] fields = { new Field("", "FUNCTION_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "FUNCTION_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "FUNCTION_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "COLUMN_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "COLUMN_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 6),
                new Field("", "TYPE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "PRECISION", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12),
                new Field("", "LENGTH", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12),
                new Field("", "SCALE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 12),
                new Field("", "RADIX", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 6),
                new Field("", "NULLABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 6),
                new Field("", "REMARKS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "CHAR_OCTET_LENGTH", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 32),
                new Field("", "ORDINAL_POSITION", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 32),
                new Field("", "IS_NULLABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 12),
                new Field("", "SPECIFIC_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 64) };
        return fields;
    }

    protected Field[] getFunctionsFields() {
        Field[] fields = new Field[6];
        fields[0] = new Field("", "FUNCTION_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[1] = new Field("", "FUNCTION_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[2] = new Field("", "FUNCTION_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[3] = new Field("", "REMARKS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        fields[4] = new Field("", "FUNCTION_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.SMALLINT, 6);
        fields[5] = new Field("", "SPECIFIC_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.CHAR, 255);
        return fields;
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException {
        return getProceduresAndOrFunctions(getFunctionsFields(), catalog, schemaPattern, functionNamePattern, false, true);
    }

    public boolean providesQueryObjectGenerator() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        return true;
    }

    /**
     * Get a prepared statement to query information_schema tables.
     *
     * @param sql
     *            query
     * @return PreparedStatement
     * @throws SQLException
     *             if a database access error occurs
     */
    protected PreparedStatement prepareMetaDataSafeStatement(String sql) throws SQLException {
        TelemetrySpan span = this.session.getTelemetryHandler().startSpan(TelemetrySpanName.STMT_PREPARE);
        try (TelemetryScope scope = span.makeCurrent()) {
            String dbOperation = QueryInfo.getStatementKeyword(sql, this.session.getServerSession().isNoBackslashEscapesSet());
            span.setAttribute(TelemetryAttribute.DB_NAME, () -> this.conn.getDatabase());
            span.setAttribute(TelemetryAttribute.DB_OPERATION, dbOperation);
            span.setAttribute(TelemetryAttribute.DB_STATEMENT, dbOperation + TelemetryAttribute.STATEMENT_SUFFIX);
            span.setAttribute(TelemetryAttribute.DB_SYSTEM, TelemetryAttribute.DB_SYSTEM_DEFAULT);
            span.setAttribute(TelemetryAttribute.DB_USER, () -> this.conn.getUser());
            span.setAttribute(TelemetryAttribute.THREAD_ID, () -> Thread.currentThread().getId());
            span.setAttribute(TelemetryAttribute.THREAD_NAME, () -> Thread.currentThread().getName());

            // Can't use server-side here as we coerce a lot of types to match the spec.
            PreparedStatement pStmt = this.conn.clientPrepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);

            if (pStmt.getMaxRows() != 0) {
                pStmt.setMaxRows(0);
            }

            ((com.mysql.cj.jdbc.JdbcStatement) pStmt).setHoldResultsOpenOverClose(true);

            return pStmt;
        } catch (Throwable t) {
            span.setError(t);
            throw t;
        } finally {
            span.end();
        }
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        Field[] fields = { new Field("", "TABLE_CAT", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "TABLE_SCHEM", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "TABLE_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "COLUMN_NAME", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "DATA_TYPE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12),
                new Field("", "COLUMN_SIZE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12),
                new Field("", "DECIMAL_DIGITS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12),
                new Field("", "NUM_PREC_RADIX", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12),
                new Field("", "COLUMN_USAGE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "REMARKS", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "CHAR_OCTET_LENGTH", this.metadataCollationIndex, this.metadataEncoding, MysqlType.INT, 12),
                new Field("", "IS_NULLABLE", this.metadataCollationIndex, this.metadataEncoding, MysqlType.VARCHAR, 512) };

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws SQLException {
        return true;
    }

    @Override
    public <T> T unwrap(java.lang.Class<T> iface) throws SQLException {
        try {
            // This works for classes that aren't actually wrapping
            // anything
            return iface.cast(this);
        } catch (ClassCastException cce) {
            throw SQLError.createSQLException(Messages.getString("Common.UnableToUnwrap", new Object[] { iface.toString() }),
                    MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT, this.conn.getExceptionInterceptor());
        }
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        // This works for classes that aren't actually wrapping anything
        return iface.isInstance(this);
    }

    @Override
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        return false;
    }

    public String getMetadataEncoding() {
        return this.metadataEncoding;
    }

    public void setMetadataEncoding(String metadataEncoding) {
        this.metadataEncoding = metadataEncoding;
    }

    public int getMetadataCollationIndex() {
        return this.metadataCollationIndex;
    }

    public void setMetadataCollationIndex(int metadataCollationIndex) {
        this.metadataCollationIndex = metadataCollationIndex;
    }

}
