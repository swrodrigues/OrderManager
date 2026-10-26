package com.ordermanager;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test-only Hibernate hook that records the SQL Hibernate generates, so tests can EXPLAIN it. */
public class SqlCapture implements StatementInspector {

    public static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }
}
