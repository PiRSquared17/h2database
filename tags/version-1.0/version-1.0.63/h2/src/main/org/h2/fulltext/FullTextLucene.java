/*
 * Copyright 2004-2007 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.fulltext;

//#ifdef JDK14
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexModifier;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Hits;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Searcher;
import org.h2.api.Trigger;
import org.h2.store.fs.FileSystem;
import org.h2.tools.SimpleResultSet;
import org.h2.util.ByteUtils;
import org.h2.util.StringUtils;
//#endif

/**
 * This class implements the full text search based on Apache Lucene.
 */
public class FullTextLucene
//#ifdef JDK14
implements Trigger
//#endif
{

//#ifdef JDK14
    private static HashMap indexers = new HashMap();
    private static final String FIELD_DATA = "DATA";
    private static final String FIELD_QUERY = "QUERY";
    private static final String FIELD_COLUMN_PREFIX = "_";
    private static final String TRIGGER_PREFIX = "FTL_";
    private static final String SCHEMA = "FTL";
    private String schemaName;
    private String tableName;
    private int[] keys;
    private int[] indexColumns;
    private String[] columnNames;
    private int[] dataTypes;
    private IndexModifier indexer;
//#endif

    /**
     * Create a new full text index for a table and column list. Each table may only have one index at any time.
     *
     * @param conn the connection
     * @param name the name of the index (must be unique)
     * @param schema the schema name of the table
     * @param table the table name
     * @param columnList the column list (null for all columns)
     */
//#ifdef JDK14
    public static void createIndex(Connection conn, String schema, String table, String columnList) throws SQLException {
        init(conn);
        PreparedStatement prep = conn.prepareStatement("INSERT INTO "+SCHEMA+".INDEXES(SCHEMA, TABLE, COLUMNS) VALUES(?, ?, ?)");
        prep.setString(1, schema);
        prep.setString(2, table);
        prep.setString(3, columnList);
        prep.execute();
        createTrigger(conn, schema, table);
        indexExistingRows(conn, schema, table);
    }
//#endif

    /**
     * Re-creates the full text index for this database
     *
     * @param conn the connection
     */
//#ifdef JDK14
    public static void reindex(Connection conn) throws SQLException {
        init(conn);
        removeAllTriggers(conn);
        removeIndexFiles(conn);
        Statement stat = conn.createStatement();
        ResultSet rs = stat.executeQuery("SELECT * FROM "+SCHEMA+".INDEXES");
        while (rs.next()) {
            String schema = rs.getString("SCHEMA");
            String table = rs.getString("TABLE");
            createTrigger(conn, schema, table);
            indexExistingRows(conn, schema, table);
        }
    }
//#endif

    /**
     * Drops all full text indexes from the database.
     *
     * @param conn the connection
     */
//#ifdef JDK14
    public static void dropAll(Connection conn) throws SQLException {
        Statement stat = conn.createStatement();
        stat.execute("DROP SCHEMA IF EXISTS " + SCHEMA);
        removeAllTriggers(conn);
        removeIndexFiles(conn);
    }
//#endif

    /**
     * Initializes full text search functionality for this database. This adds the following Java functions to the
     * database:
     * <ul>
     * <li>FTL_CREATE_INDEX(schemaNameString, tableNameString, columnListString)
     * </li><li>FTL_SEARCH(queryString, limitInt, offsetInt): result set
     * </li><li>FTL_REINDEX()
     * </li><li>FTL_DROP_ALL()
     * </li></ul>
     * It also adds a schema FTL to the database where bookkeeping information is stored. This function may be
     * called from a Java application, or by using the SQL statements:
     *
     * <pre>
     *  CREATE ALIAS IF NOT EXISTS FTL_INIT FOR &quot;org.h2.fulltext.FullTextLucene.init&quot;;
     *  CALL FTL_INIT();
     * </pre>
     *
     * @param conn
     */
//#ifdef JDK14
    public static void init(Connection conn) throws SQLException {
        Statement stat = conn.createStatement();
        stat.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        stat.execute("CREATE TABLE IF NOT EXISTS "+SCHEMA+".INDEXES(SCHEMA VARCHAR, TABLE VARCHAR, COLUMNS VARCHAR, PRIMARY KEY(SCHEMA, TABLE))");
        stat.execute("CREATE ALIAS IF NOT EXISTS FTL_CREATE_INDEX FOR \"" + FullTextLucene.class.getName() + ".createIndex\"");
        stat.execute("CREATE ALIAS IF NOT EXISTS FTL_SEARCH FOR \"" + FullTextLucene.class.getName() + ".search\"");
        stat.execute("CREATE ALIAS IF NOT EXISTS FTL_REINDEX FOR \"" + FullTextLucene.class.getName() + ".reindex\"");
        stat.execute("CREATE ALIAS IF NOT EXISTS FTL_DROP_ALL FOR \"" + FullTextLucene.class.getName() + ".dropAll\"");
    }
//#endif

    /**
     * INTERNAL
     */
//#ifdef JDK14
    public void init(Connection conn, String schemaName, String triggerName, String tableName) throws SQLException {
        init(conn);
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.indexer = getIndexModifier(conn);
        ArrayList keyList = new ArrayList();
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getColumns(null, schemaName, tableName, null);
        ArrayList columnList = new ArrayList();
        while (rs.next()) {
            columnList.add(rs.getString("COLUMN_NAME"));
        }
        dataTypes = new int[columnList.size()];
        columnNames = new String[columnList.size()];
        columnList.toArray(columnNames);
        rs = meta.getColumns(null, schemaName, tableName, null);
        for (int i = 0; rs.next(); i++) {
            dataTypes[i] = rs.getInt("DATA_TYPE");
        }
        if (keyList.size() == 0) {
            rs = meta.getPrimaryKeys(null, schemaName, tableName);
            while (rs.next()) {
                keyList.add(rs.getString("COLUMN_NAME"));
            }
        }
        if (keyList.size() == 0) {
            throw new SQLException("No primary key for table " + tableName);
        }
        ArrayList indexList = new ArrayList();
        PreparedStatement prep = conn.prepareStatement("SELECT COLUMNS FROM "+SCHEMA+".INDEXES WHERE SCHEMA=? AND TABLE=?");
        prep.setString(1, schemaName);
        prep.setString(2, tableName);
        rs = prep.executeQuery();
        if (rs.next()) {
            String columns = rs.getString(1);
            if (columns != null) {
                String[] list = StringUtils.arraySplit(columns, ',', true);
                for (int i = 0; i < list.length; i++) {
                    indexList.add(list[i]);
                }
            }
        }
        if (indexList.size() == 0) {
            indexList.addAll(columnList);
        }
        keys = new int[keyList.size()];
        setColumns(keys, keyList, columnList);
        indexColumns = new int[indexList.size()];
        setColumns(indexColumns, indexList, columnList);
    }
//#endif

    /**
     * INTERNAL
     */
//#ifdef JDK14
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        if (oldRow != null) {
            delete(oldRow);
        }
        if (newRow != null) {
            insert(newRow);
        }
    }
//#endif

    /**
     * Searches from the full text index for this database.
     *
     * @param conn the connection
     * @param text the search query
     * @param limit the maximum number of rows or 0 for no limit
     * @param offset the offset or 0 for no offset
     * @return the result set
     */
//#ifdef JDK14
    public static ResultSet search(Connection conn, String text, int limit, int offset) throws SQLException {
        SimpleResultSet rs = new SimpleResultSet();
        rs.addColumn(FIELD_QUERY, Types.VARCHAR, 0, 0);
        if (text == null) {
            // this is just to query the result set columns
            return rs;
        }
        String path = getIndexPath(conn);
        try {
            IndexModifier indexer = getIndexModifier(conn);
            indexer.flush();
            IndexReader reader = IndexReader.open(path);
            Analyzer analyzer = new StandardAnalyzer();
            Searcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser(FIELD_DATA, analyzer);
            Query query = parser.parse(text);
            Hits hits = searcher.search(query);
            int max = hits.length();
            if (limit == 0) {
                limit = max;
            }
            for (int i = 0; i < limit && i + offset < max; i++) {
                Document doc = hits.doc(i + offset);
                String q = doc.get(FIELD_QUERY);
                rs.addRow(new Object[] { q });
            }
            // TODO keep it open if possible
            reader.close();
        } catch (Exception e) {
            throw convertException(e);
        }
        return rs;
    }

    private static void removeAllTriggers(Connection conn) throws SQLException {
        Statement stat = conn.createStatement();
        ResultSet rs = stat.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TRIGGERS");
        Statement stat2 = conn.createStatement();
        while (rs.next()) {
            String schema = rs.getString("TRIGGER_SCHEMA");
            String name = rs.getString("TRIGGER_NAME");
            if (name.startsWith(TRIGGER_PREFIX)) {
                name = StringUtils.quoteIdentifier(schema) + "." + StringUtils.quoteIdentifier(name);
                stat2.execute("DROP TRIGGER " + name);
            }
        }
    }

    private static void removeIndexFiles(Connection conn) throws SQLException {
        String path = getIndexPath(conn);
        IndexModifier index = (IndexModifier) indexers.get(path);
        if (index != null) {
            indexers.remove(path);
            try {
                index.flush();
                index.close();
            } catch (IOException e) {
                throw convertException(e);
            }
        }
        FileSystem.getInstance(path).deleteRecursive(path);
    }

    private String getQuery(Object[] row) throws SQLException {
        StringBuffer buff = new StringBuffer();
        if (schemaName != null) {
            buff.append(StringUtils.quoteIdentifier(schemaName));
            buff.append(".");
        }
        buff.append(StringUtils.quoteIdentifier(tableName));
        buff.append(" WHERE ");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                buff.append(" AND ");
            }
            int columnIndex = keys[i];
            buff.append(StringUtils.quoteIdentifier(columnNames[columnIndex]));
            Object o = row[columnIndex];
            if (o == null) {
                buff.append(" IS NULL");
            } else {
                buff.append("=");
                buff.append(quoteSQL(o, dataTypes[columnIndex]));
            }
        }
        String key = buff.toString();
        return key;
    }

    private String quoteString(String data) {
        if (data.indexOf('\'') < 0) {
            return "'" + data + "'";
        }
        StringBuffer buff = new StringBuffer(data.length() + 2);
        buff.append('\'');
        for (int i = 0; i < data.length(); i++) {
            char ch = data.charAt(i);
            if (ch == '\'') {
                buff.append(ch);
            }
            buff.append(ch);
        }
        buff.append('\'');
        return buff.toString();
    }

    private String quoteBinary(byte[] data) {
        return "'" + ByteUtils.convertBytesToString(data) + "'";
    }

    private String asString(Object data, int type) throws SQLException {
        if (data == null) {
            return "NULL";
        }
        switch (type) {
        case Types.BIT:
        case Types.BOOLEAN:
        case Types.INTEGER:
        case Types.BIGINT:
        case Types.DECIMAL:
        case Types.DOUBLE:
        case Types.FLOAT:
        case Types.NUMERIC:
        case Types.REAL:
        case Types.SMALLINT:
        case Types.TINYINT:
        case Types.DATE:
        case Types.TIME:
        case Types.TIMESTAMP:
        case Types.LONGVARCHAR:
        case Types.CHAR:
        case Types.VARCHAR:
            return data.toString();
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.BINARY:
        case Types.JAVA_OBJECT:
        case Types.CLOB:
        case Types.OTHER:
        case Types.BLOB:
        case Types.STRUCT:
        case Types.REF:
        case Types.NULL:
        case Types.ARRAY:
        case Types.DATALINK:
        case Types.DISTINCT:
            throw new SQLException("FULLTEXT", "Unsupported column data type: " + type);
        }
        return "";
    }

    private String quoteSQL(Object data, int type) throws SQLException {
        if (data == null) {
            return "NULL";
        }
        switch (type) {
        case Types.BIT:
        case Types.BOOLEAN:
        case Types.INTEGER:
        case Types.BIGINT:
        case Types.DECIMAL:
        case Types.DOUBLE:
        case Types.FLOAT:
        case Types.NUMERIC:
        case Types.REAL:
        case Types.SMALLINT:
        case Types.TINYINT:
            return data.toString();
        case Types.DATE:
        case Types.TIME:
        case Types.TIMESTAMP:
        case Types.LONGVARCHAR:
        case Types.CHAR:
        case Types.VARCHAR:
            return quoteString(data.toString());
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.BINARY:
            return quoteBinary((byte[]) data);
        case Types.JAVA_OBJECT:
        case Types.CLOB:
        case Types.OTHER:
        case Types.BLOB:
        case Types.STRUCT:
        case Types.REF:
        case Types.NULL:
        case Types.ARRAY:
        case Types.DATALINK:
        case Types.DISTINCT:
            throw new SQLException("FULLTEXT", "Unsupported key data type: " + type);
        }
        return "";
    }

    private void insert(Object[] row) throws SQLException {
        String query = getQuery(row);
        Document doc = new Document();
        doc.add(new Field(FIELD_QUERY, query, Field.Store.YES, Field.Index.UN_TOKENIZED));
        long time = System.currentTimeMillis();
        doc.add(new Field("modified", DateTools.timeToString(time, DateTools.Resolution.SECOND), Field.Store.YES, Field.Index.UN_TOKENIZED));
        StringBuffer allData = new StringBuffer();
        for (int i = 0; i < indexColumns.length; i++) {
            int index = indexColumns[i];
            String columnName = columnNames[index];
            String data = asString(row[index], dataTypes[index]);
            doc.add(new Field(FIELD_COLUMN_PREFIX + columnName, data, Field.Store.NO, Field.Index.TOKENIZED));
            if (i > 0) {
                allData.append(" ");
            }
            allData.append(data);
        }
        doc.add(new Field(FIELD_DATA, allData.toString(), Field.Store.NO, Field.Index.TOKENIZED));
        try {
            indexer.addDocument(doc);
        } catch (IOException e) {
            throw convertException(e);
        }
    }

    private void delete(Object[] row) throws SQLException {
        String query = getQuery(row);
        try {
            Term term = new Term(FIELD_QUERY, query);
            indexer.deleteDocuments(term);
        } catch (IOException e) {
            throw convertException(e);
        }
    }

    private static SQLException convertException(Exception e) {
        SQLException e2 = new SQLException("FULLTEXT", "Error while indexing document");
        e2.initCause(e);
        return e2;
    }

    private static void createTrigger(Connection conn, String schema, String table) throws SQLException {
        Statement stat = conn.createStatement();
        String trigger = StringUtils.quoteIdentifier(schema) + "." + StringUtils.quoteIdentifier(TRIGGER_PREFIX + table);
        stat.execute("DROP TRIGGER IF EXISTS " + trigger);
        StringBuffer buff = new StringBuffer("CREATE TRIGGER IF NOT EXISTS ");
        buff.append(trigger);
        buff.append(" AFTER INSERT, UPDATE, DELETE ON ");
        buff.append(StringUtils.quoteIdentifier(schema) + "." + StringUtils.quoteIdentifier(table));
        buff.append(" FOR EACH ROW CALL \"");
        buff.append(FullTextLucene.class.getName());
        buff.append("\"");
        stat.execute(buff.toString());
    }

    private static void indexExistingRows(Connection conn, String schema, String table) throws SQLException {
        FullTextLucene existing = new FullTextLucene();
        existing.init(conn, schema, null, table);
        StringBuffer buff = new StringBuffer("SELECT * FROM ");
        buff.append(StringUtils.quoteIdentifier(schema) + "." + StringUtils.quoteIdentifier(table));
        ResultSet rs = conn.createStatement().executeQuery(buff.toString());
        int columnCount = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            Object[] row = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                row[i] = rs.getObject(i + 1);
            }
            existing.fire(conn, null, row);
        }
    }

    private static IndexModifier getIndexModifier(Connection conn) throws SQLException {
        try {
            String path = getIndexPath(conn);
            IndexModifier indexer;
            synchronized (indexers) {
                indexer = (IndexModifier) indexers.get(path);
                if (indexer == null) {
                    // TODO: create flag = true means re-create
                    indexer = new IndexModifier(path, new StandardAnalyzer(), true);
                    indexers.put(path, indexer);
                }
            }
            return indexer;
        } catch (IOException e) {
            throw convertException(e);
        }
    }

    private static String getIndexPath(Connection conn) throws SQLException {
        Statement stat = conn.createStatement();
        ResultSet rs = stat.executeQuery("CALL DATABASE_PATH()");
        rs.next();
        String path = rs.getString(1);
        if (path == null) {
            throw new SQLException("FULLTEXT", "Fulltext search for in-memory databases is not supported.");
        }
        rs.close();
        return path;
    }

    private void setColumns(int[] index, ArrayList keys, ArrayList columns) throws SQLException {
        for (int i = 0; i < keys.size(); i++) {
            String key = (String) keys.get(i);
            int found = -1;
            for (int j = 0; found == -1 && j < columns.size(); j++) {
                String column = (String) columns.get(j);
                if (column.equals(key)) {
                    found = j;
                }
            }
            if (found < 0) {
                throw new SQLException("FULLTEXT", "Column not found: " + key);
            }
            index[i] = found;
        }
    }
//#endif

}