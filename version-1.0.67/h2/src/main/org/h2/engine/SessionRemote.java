/*
 * Copyright 2004-2008 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import java.io.IOException;
import java.net.Socket;
import java.sql.SQLException;

import org.h2.command.CommandInterface;
import org.h2.command.CommandRemote;
import org.h2.command.dml.SetTypes;
import org.h2.constant.ErrorCode;
import org.h2.constant.SysProperties;
import org.h2.jdbc.JdbcSQLException;
import org.h2.message.Message;
import org.h2.message.Trace;
import org.h2.message.TraceSystem;
import org.h2.store.DataHandler;
import org.h2.store.FileStore;
import org.h2.util.FileUtils;
import org.h2.util.NetUtils;
import org.h2.util.ObjectArray;
import org.h2.util.RandomUtils;
import org.h2.util.StringUtils;
import org.h2.value.Transfer;
import org.h2.value.Value;

/**
 * The client side part of a session when using the server mode.
 * This object communicates with a Session on the server side.
 */
public class SessionRemote implements SessionInterface, DataHandler {

    public static final int SESSION_PREPARE = 0;
    public static final int SESSION_CLOSE = 1;
    public static final int COMMAND_EXECUTE_QUERY = 2;
    public static final int COMMAND_EXECUTE_UPDATE = 3;
    public static final int COMMAND_CLOSE = 4;
    public static final int RESULT_FETCH_ROWS = 5;
    public static final int RESULT_RESET = 6;
    public static final int RESULT_CLOSE = 7;
    public static final int COMMAND_COMMIT = 8;
    public static final int CHANGE_ID = 9;
    public static final int COMMAND_GET_META_DATA = 10;

    public static final int STATUS_ERROR = 0;
    public static final int STATUS_OK = 1;
    public static final int STATUS_CLOSED = 2;

    private TraceSystem traceSystem;
    private Trace trace;
    private ObjectArray transferList;
    private int nextId;
    private boolean autoCommit = true;
    private CommandInterface switchOffAutoCommit;
    private ConnectionInfo connectionInfo;
    private int objectId;
    private String databaseName;
    private String cipher;
    private byte[] fileEncryptionKey;
    private Object lobSyncObject = new Object();

    private Transfer initTransfer(ConnectionInfo ci, String db, String server) throws IOException, SQLException {
        Socket socket = NetUtils.createSocket(server, Constants.DEFAULT_SERVER_PORT, ci.isSSL());
        Transfer trans = new Transfer(this);
        trans.setSocket(socket);
        trans.init();
        trans.writeInt(Constants.TCP_DRIVER_VERSION);
        trans.writeString(db);
        trans.writeString(ci.getOriginalURL());
        trans.writeString(ci.getUserName());
        trans.writeBytes(ci.getUserPasswordHash());
        trans.writeBytes(ci.getFilePasswordHash());
        String[] keys = ci.getKeys();
        trans.writeInt(keys.length);
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            trans.writeString(key).writeString(ci.getProperty(key));
        }
        try {
            done(trans);
        } catch (SQLException e) {
            trans.close();
            throw e;
        }
        autoCommit = true;
        return trans;
    }

    private void switchOffAutoCommitIfCluster() throws SQLException {
        if (autoCommit && transferList.size() > 1) {
            if (switchOffAutoCommit == null) {
                switchOffAutoCommit = prepareCommand("SET AUTOCOMMIT FALSE", Integer.MAX_VALUE);
            }
            // this will call setAutoCommit(false)
            switchOffAutoCommit.executeUpdate();
            // so we need to switch it on
            autoCommit = true;
        }
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    public void autoCommitIfCluster() throws SQLException {
        if (autoCommit && transferList != null && transferList.size() > 1) {
            // server side auto commit is off because of race conditions
            // (update set id=1 where id=0, but update set id=2 where id=0 is
            // faster)
            for (int i = 0; i < transferList.size(); i++) {
                Transfer transfer = (Transfer) transferList.get(i);
                try {
                    traceOperation("COMMAND_COMMIT", 0);
                    transfer.writeInt(SessionRemote.COMMAND_COMMIT);
                    done(transfer);
                } catch (IOException e) {
                    removeServer(i--);
                }
            }
        }
    }

    private String getTraceFilePrefix(String dbName) throws SQLException {
        String dir = SysProperties.CLIENT_TRACE_DIRECTORY;
        StringBuffer buff = new StringBuffer();
        buff.append(dir);
        for (int i = 0; i < dbName.length(); i++) {
            char ch = dbName.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                buff.append(ch);
            } else {
                buff.append('_');
            }
        }
        return buff.toString();
    }

    public SessionRemote() {
    }

    public int getPowerOffCount() {
        return 0;
    }

    public void setPowerOffCount(int count) throws SQLException {
        throw Message.getUnsupportedException();
    }

    public SessionInterface createSession(ConnectionInfo ci) throws SQLException {
        return new SessionRemote(ci);
    }

    private SessionRemote(ConnectionInfo ci) throws SQLException {
        this.connectionInfo = ci;
        connect();
    }

    private void connect() throws SQLException {
        ConnectionInfo ci = connectionInfo;
        String name = ci.getName();
        if (name.startsWith("//")) {
            name = name.substring("//".length());
        }
        int idx = name.indexOf('/');
        if (idx < 0) {
            throw ci.getFormatException();
        }
        databaseName = name.substring(idx + 1);
        String server = name.substring(0, idx);
        traceSystem = new TraceSystem(null, false);
        try {
            String traceLevelFile = ci.getProperty(SetTypes.TRACE_LEVEL_FILE, null);
            if (traceLevelFile != null) {
                int level = Integer.parseInt(traceLevelFile);
                String prefix = getTraceFilePrefix(databaseName);
                String file = FileUtils.createTempFile(prefix, Constants.SUFFIX_TRACE_FILE, false, false);
                traceSystem.setFileName(file);
                traceSystem.setLevelFile(level);
            }
            String traceLevelSystemOut = ci.getProperty(SetTypes.TRACE_LEVEL_SYSTEM_OUT, null);
            if (traceLevelSystemOut != null) {
                int level = Integer.parseInt(traceLevelSystemOut);
                traceSystem.setLevelSystemOut(level);
            }
        } catch (Exception e) {
            throw Message.convert(e);
        }
        trace = traceSystem.getTrace(Trace.JDBC);
        transferList = new ObjectArray();
        String serverlist = null;
        if (server.indexOf(',') >= 0) {
            serverlist = StringUtils.quoteStringSQL(server);
            ci.setProperty("CLUSTER", serverlist);
        }
        cipher = ci.getProperty("CIPHER");
        if (cipher != null) {
            fileEncryptionKey = RandomUtils.getSecureBytes(32);
        }
        String[] servers = StringUtils.arraySplit(server, ',', true);
        int len = servers.length;
        transferList = new ObjectArray();
        // TODO cluster: support at most 2 connections
        boolean switchOffCluster = false;
        try {
            for (int i = 0; i < len; i++) {
                try {
                    Transfer trans = initTransfer(ci, databaseName, servers[i]);
                    transferList.add(trans);
                } catch (IOException e) {
                    switchOffCluster = true;
                }
            }
            checkClosed();
            if (switchOffCluster) {
                switchOffCluster();
            }
            switchOffAutoCommitIfCluster();
        } catch (SQLException e) {
            traceSystem.close();
            throw e;
        }
    }

    private void switchOffCluster() throws SQLException {
        CommandInterface ci = prepareCommand("SET CLUSTER ''", Integer.MAX_VALUE);
        ci.executeUpdate();
    }

    public void removeServer(int i) throws SQLException {
        transferList.remove(i);
        checkClosed();
        switchOffCluster();
    }

    public CommandInterface prepareCommand(String sql, int fetchSize) throws SQLException {
        synchronized (this) {
            checkClosed();
            return new CommandRemote(this, transferList, sql, fetchSize);
        }
    }

    public void checkClosed() throws SQLException {
        if (isClosed()) {
            // TODO broken connection: try to reconnect automatically
            throw Message.getSQLException(ErrorCode.CONNECTION_BROKEN);
        }
    }

    public void close() {
        if (transferList != null) {
            synchronized (this) {
                for (int i = 0; i < transferList.size(); i++) {
                    Transfer transfer = (Transfer) transferList.get(i);
                    try {
                        traceOperation("SESSION_CLOSE", 0);
                        transfer.writeInt(SessionRemote.SESSION_CLOSE);
                        done(transfer);
                        transfer.close();
                    } catch (Exception e) {
                        trace.error("close", e);
                    }
                }
            }
            transferList = null;
        }
        traceSystem.close();
    }

    public Trace getTrace() {
        return traceSystem.getTrace(Trace.JDBC);
    }

    public int getNextId() {
        return nextId++;
    }

    public int getCurrentId() {
        return nextId;
    }

    public void done(Transfer transfer) throws SQLException, IOException {
        transfer.flush();
        int status = transfer.readInt();
        if (status == STATUS_ERROR) {
            String sqlstate = transfer.readString();
            String message = transfer.readString();
            String sql = transfer.readString();
            int errorCode = transfer.readInt();
            String trace = transfer.readString();
            throw new JdbcSQLException(message, sql, sqlstate, errorCode, null, trace);
        } else if (status == STATUS_CLOSED) {
            transferList = null;
        }
    }

    public boolean isClustered() {
        return transferList.size() > 1;
    }

    public boolean isClosed() {
        return transferList == null || transferList.size() == 0;
    }

    public void traceOperation(String operation, int id) {
        if (trace.debug()) {
            trace.debug(operation + " " + id);
        }
    }

    public int allocateObjectId(boolean needFresh, boolean dataFile) {
        return objectId++;
    }

    public void checkPowerOff() throws SQLException {
    }

    public void checkWritingAllowed() throws SQLException {
    }

    public int compareTypeSave(Value a, Value b) throws SQLException {
        throw Message.getInternalError();
    }

    public String createTempFile() throws SQLException {
        try {
            return FileUtils.createTempFile(databaseName, Constants.SUFFIX_TEMP_FILE, true, false);
        } catch (IOException e) {
            throw Message.convertIOException(e, databaseName);
        }
    }

    public void freeUpDiskSpace() throws SQLException {
    }

    public int getChecksum(byte[] data, int start, int end) {
        return 0;
    }

    public String getDatabasePath() {
        return "";
    }

    public String getLobCompressionAlgorithm(int type) {
        return null;
    }

    public int getMaxLengthInplaceLob() {
        return Constants.DEFAULT_MAX_LENGTH_CLIENTSIDE_LOB;
    }

    public boolean getTextStorage() {
        return false;
    }

    public void handleInvalidChecksum() throws SQLException {
        throw Message.getSQLException(ErrorCode.FILE_CORRUPTED_1, "wrong checksum");
    }

    public FileStore openFile(String name, String mode, boolean mustExist) throws SQLException {
        if (mustExist && !FileUtils.exists(name)) {
            throw Message.getSQLException(ErrorCode.FILE_CORRUPTED_1, name);
        }
        FileStore store;
        byte[] magic = Constants.MAGIC_FILE_HEADER.getBytes();
        if (cipher == null) {
            store = FileStore.open(this, name, mode, magic);
        } else {
            store = FileStore.open(this, name, mode, magic, cipher, fileEncryptionKey, 0);
        }
        store.setCheckedWriting(false);
        try {
            store.init();
        } catch (SQLException e) {
            store.closeSilently();
            throw e;
        }
        return store;
    }

    public DataHandler getDataHandler() {
        return this;
    }

    public Object getLobSyncObject() {
        return lobSyncObject;
    }

    public void cancel() {
        // TODO open another remote connection and cancel this session using a unique id (like PostgreSQL)
    }

}
