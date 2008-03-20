/*
 * Copyright 2004-2008 H2 Group. Licensed under the H2 License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.tools;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.h2.engine.Constants;
import org.h2.message.Message;
import org.h2.util.ClassUtils;
import org.h2.util.FileUtils;
import org.h2.util.IOUtils;
import org.h2.util.JdbcUtils;
import org.h2.util.ScriptReader;
import org.h2.util.StringUtils;

/**
 * Executes the contents of a SQL script file against a database.
 */
public class RunScript {

    private void showUsage() {
        System.out.println("java " + getClass().getName() + " -url <url> -user <user> [-password <pwd>] [-script <file>] [-driver <driver] [-options <option> ...]");
        System.out.println("See also http://h2database.com/javadoc/org/h2/tools/RunScript.html");
    }

    /**
     * The command line interface for this tool. The options must be split into
     * strings like this: "-user", "sa",... Options are case sensitive. The
     * following options are supported:
     * <ul>
     * <li>-help or -? (print the list of options) </li>
     * <li>-url jdbc:h2:... (database URL) </li>
     * <li>-user username </li>
     * <li>-password password </li>
     * <li>-script filename (default file name is backup.sql) </li>
     * <li>-driver driver the JDBC driver class name (not required for most
     * databases) </li>
     * <li>-options to specify a list of options (only for H2 and only when
     * using the embedded mode) </li>
     * </ul>
     * To include local files when using remote databases, use the special
     * syntax:
     * 
     * <pre>
     * &#064;INCLUDE fileName
     * </pre>
     * 
     * This syntax is only supported by this tool. Embedded RUNSCRIPT SQL
     * statements will be executed by the database.
     * 
     * @param args the command line arguments
     * @throws SQLException
     */
    public static void main(String[] args) throws SQLException {
        new RunScript().run(args);
    }

    private void run(String[] args) throws SQLException {
        String url = null;
        String user = null;
        String password = "";
        String script = "backup.sql";
        String options = null;
        boolean continueOnError = false;
        boolean showTime = false;
        for (int i = 0; args != null && i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-url")) {
                url = args[++i];
            } else if (arg.equals("-user")) {
                user = args[++i];
            } else if (arg.equals("-password")) {
                password = args[++i];
            } else if (arg.equals("-continueOnError")) {
                continueOnError = true;
            } else if (arg.equals("-script")) {
                script = args[++i];
            } else if (arg.equals("-time")) {
                showTime = true;
            } else if (arg.equals("-driver")) {
                String driver = args[++i];
                try {
                    ClassUtils.loadUserClass(driver);
                } catch (ClassNotFoundException e) {
                    throw Message.convert(e);
                }
            } else if (arg.equals("-options")) {
                StringBuffer buff = new StringBuffer();
                i++;
                for (; i < args.length; i++) {
                    buff.append(' ');
                    buff.append(args[i]);
                }
                options = buff.toString();
            } else if (arg.equals("-help") || arg.equals("-?")) {
                showUsage();
                return;
            } else {
                System.out.println("Unsupported option: " + arg);
                showUsage();
                return;
            }
        }
        if (url == null || user == null || password == null || script == null) {
            showUsage();
            return;
        }
        long time = System.currentTimeMillis();
        if (options != null) {
            executeRunscript(url, user, password, script, options);
        } else {
            execute(url, user, password, script, null, continueOnError);
        }
        if (showTime) {
            time = System.currentTimeMillis() - time;
            System.out.println("Done in " + time + " ms");
        }
    }

    /**
     * Executes the SQL commands in a script file against a database.
     *
     * @param conn the connection to a database
     * @param reader the reader
     * @return the last result set
     */
    public static ResultSet execute(Connection conn, Reader reader) throws SQLException {
        reader = new BufferedReader(reader);
        Statement stat = conn.createStatement();
        ResultSet rs = null;
        ScriptReader r = new ScriptReader(reader);
        while (true) {
            String sql = r.readStatement();
            if (sql == null) {
                break;
            }
            boolean resultSet = stat.execute(sql);
            if (resultSet) {
                if (rs != null) {
                    rs.close();
                    rs = null;
                }
                rs = stat.getResultSet();
            }
        }
        return rs;
    }

    private static void execute(Connection conn, String fileName, boolean continueOnError, String charsetName) throws SQLException, IOException {
        InputStream in = FileUtils.openFileInputStream(fileName);
        String path = FileUtils.getParent(fileName);
        try {
            in = new BufferedInputStream(in, Constants.IO_BUFFER_SIZE);
            Reader reader = new InputStreamReader(in, charsetName);
            execute(conn, continueOnError, path, reader, charsetName);
        } finally {
            IOUtils.closeSilently(in);
        }
    }

    private static void execute(Connection conn, boolean continueOnError, String path, Reader reader, String charsetName) throws SQLException, IOException {
        Statement stat = conn.createStatement();
        ScriptReader r = new ScriptReader(new BufferedReader(reader));
        while (true) {
            String sql = r.readStatement();
            if (sql == null) {
                break;
            }
            sql = sql.trim();
            if (sql.startsWith("@") && StringUtils.toUpperEnglish(sql).startsWith("@INCLUDE")) {
                sql = sql.substring("@INCLUDE".length()).trim();
                if (!FileUtils.isAbsolute(sql)) {
                    sql = path + File.separator + sql;
                }
                execute(conn, sql, continueOnError, charsetName);
            } else {
                try {
                    if (sql.trim().length() > 0) {
                        stat.execute(sql);
                    }
                } catch (SQLException e) {
                    if (continueOnError) {
                        e.printStackTrace();
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    private static void executeRunscript(String url, String user, String password, String fileName, String options) throws SQLException {
        Connection conn = null;
        Statement stat = null;
        try {
            org.h2.Driver.load();
            conn = DriverManager.getConnection(url, user, password);
            stat = conn.createStatement();
            String sql = "RUNSCRIPT FROM '" + fileName + "' " + options;
            stat.execute(sql);
        } finally {
            JdbcUtils.closeSilently(stat);
            JdbcUtils.closeSilently(conn);
        }
    }

    /**
     * Executes the SQL commands in a script file against a database.
     *
     * @param url the database URL
     * @param user the user name
     * @param password the password
     * @param fileName the script file
     * @param charsetName the character set name or null for UTF-8
     * @param continueOnError if execution should be continued if an error occurs
     * @throws SQLException
     */
    public static void execute(String url, String user, String password, String fileName, String charsetName, boolean continueOnError) throws SQLException {
        try {
            org.h2.Driver.load();
            Connection conn = DriverManager.getConnection(url, user, password);
            if (charsetName == null) {
                charsetName = Constants.UTF8;
            }
            try {
                execute(conn, fileName, continueOnError, charsetName);
            } finally {
                conn.close();
            }
        } catch (IOException e) {
            throw Message.convertIOException(e, fileName);
        }
    }

}
