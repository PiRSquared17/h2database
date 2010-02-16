/*
 * Copyright 2004-2010 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.jdbcx;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.naming.Reference;
import javax.naming.StringRefAddr;
import javax.naming.spi.ObjectFactory;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.jdbcx.JdbcDataSourceFactory;
import org.h2.test.TestBase;

/**
 * Tests DataSource and XAConnection.
 */
public class TestDataSource extends TestBase {

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().test();
    }

//     public static void main(String... args) throws SQLException {
//
//     // first, need to start on the command line:
//     // rmiregistry 1099
//
//     // System.setProperty(Context.INITIAL_CONTEXT_FACTORY,
//     "com.sun.jndi.ldap.LdapCtxFactory");
//     System.setProperty(Context.INITIAL_CONTEXT_FACTORY,
//     "com.sun.jndi.rmi.registry.RegistryContextFactory");
//     System.setProperty(Context.PROVIDER_URL, "rmi://localhost:1099");
//
//     JdbcDataSource ds = new JdbcDataSource();
//     ds.setURL("jdbc:h2:test");
//     ds.setUser("test");
//     ds.setPassword("");
//
//     Context ctx = new InitialContext();
//     ctx.bind("jdbc/test", ds);
//
//     DataSource ds2 = (DataSource)ctx.lookup("jdbc/test");
//     Connection conn = ds2.getConnection();
//     conn.close();
//     }

    public void test() throws Exception {
        testDataSourceFactory();
        testDataSource();
        testXAConnection();
        deleteDb("dataSource");
    }

    private void testDataSourceFactory() throws Exception {
        ObjectFactory factory = new JdbcDataSourceFactory();
        assertTrue(null == factory.getObjectInstance("test", null, null, null));
        Reference ref = new Reference("java.lang.String");
        assertTrue(null == factory.getObjectInstance(ref, null, null, null));
        ref = new Reference(JdbcDataSource.class.getName());
        ref.add(new StringRefAddr("url", "jdbc:h2:mem:"));
        ref.add(new StringRefAddr("user", "u"));
        ref.add(new StringRefAddr("password", "p"));
        ref.add(new StringRefAddr("loginTimeout", "1"));
        JdbcDataSource ds = (JdbcDataSource) factory.getObjectInstance(ref, null, null, null);
        assertEquals(1, ds.getLoginTimeout());
        assertEquals("jdbc:h2:mem:", ds.getURL());
        assertEquals("u", ds.getUser());
        assertEquals("p", ds.getPassword());
        Reference ref2 = ds.getReference();
        assertEquals(ref.size(), ref2.size());
        assertEquals(ref.get("url").getContent().toString(), ref2.get("url").getContent().toString());
        assertEquals(ref.get("user").getContent().toString(), ref2.get("user").getContent().toString());
        assertEquals(ref.get("password").getContent().toString(), ref2.get("password").getContent().toString());
        assertEquals(ref.get("loginTimeout").getContent().toString(), ref2.get("loginTimeout").getContent().toString());
        ds.setPasswordChars("abc".toCharArray());
        assertEquals("abc", ds.getPassword());
    }

    private void testXAConnection() throws Exception {
        deleteDb("dataSource");
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(getURL("dataSource", true));
        ds.setUser(getUser());
        ds.setPassword(getPassword());
        XAConnection xaConn = ds.getXAConnection();
        xaConn.addConnectionEventListener(new ConnectionEventListener() {
            public void connectionClosed(ConnectionEvent event) {
                // nothing to do
            }

            public void connectionErrorOccurred(ConnectionEvent event) {
                // nothing to do
            }
        });
        XAResource res = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        Xid[] list = res.recover(XAResource.TMSTARTRSCAN);
        assertEquals(0, list.length);
        Statement stat = conn.createStatement();
        stat.execute("SELECT * FROM DUAL");
        conn.close();
        xaConn.close();
    }

    private void testDataSource() throws SQLException {
        deleteDb("dataSource");
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(getURL("dataSource", true));
        ds.setUser(getUser());
        ds.setPassword(getPassword());
        Connection conn = ds.getConnection();
        Statement stat = conn.createStatement();
        stat.execute("SELECT * FROM DUAL");
        conn.close();
    }

}
