/*
 * Copyright 2004-2008 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.ddl;

import java.sql.SQLException;

import org.h2.constant.ErrorCode;
import org.h2.engine.Database;
import org.h2.engine.Right;
import org.h2.engine.Session;
import org.h2.message.Message;
import org.h2.schema.Schema;
import org.h2.table.Table;

/**
 * This class represents the statement
 * ALTER TABLE RENAME
 */
public class AlterTableRename extends SchemaCommand {

    private Table oldTable;
    private String newTableName;

    public AlterTableRename(Session session, Schema schema) {
        super(session, schema);
    }

    public void setOldTable(Table table) {
        oldTable = table;
    }

    public void setNewTableName(String name) {
        newTableName = name;
    }

    public int update() throws SQLException {
        session.commit(true);
        Database db = session.getDatabase();
        if (getSchema().findTableOrView(session, newTableName) != null || newTableName.equals(oldTable.getName())) {
            throw Message.getSQLException(ErrorCode.TABLE_OR_VIEW_ALREADY_EXISTS_1, newTableName);
        }
        session.getUser().checkRight(oldTable, Right.ALL);
        if (oldTable.getTemporary()) {
            // TODO renaming a temporary table is not supported
            throw Message.getUnsupportedException();
        }
        db.renameSchemaObject(session, oldTable, newTableName);
        return 0;
    }

}