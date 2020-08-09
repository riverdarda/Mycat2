package io.mycat.metadata;

import com.alibaba.fastsql.sql.SQLUtils;
import com.alibaba.fastsql.sql.ast.SQLStatement;
import com.alibaba.fastsql.sql.ast.statement.SQLExprTableSource;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlDeleteStatement;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;
import io.mycat.*;
import io.mycat.plug.loadBalance.LoadBalanceInfo;
import io.mycat.plug.loadBalance.LoadBalanceStrategy;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class GlobalTable implements GlobalTableHandler {
    private final LogicTable logicTable;
    private final List<DataNode> backendTableInfos;
    private final LoadBalanceStrategy balance;


    public GlobalTable(LogicTable logicTable,
                       List<DataNode> backendTableInfos,
                       LoadBalanceStrategy balance) {
        this.logicTable = logicTable;
        this.backendTableInfos = backendTableInfos;
        this.balance = balance;
    }

    @Override
    public Function<ParseContext, Iterator<TextUpdateInfo>> insertHandler() {
        return new Function<ParseContext, Iterator<TextUpdateInfo>>() {
            @Override
            public Iterator<TextUpdateInfo> apply(ParseContext s) {
                SQLStatement sqlStatement = SQLUtils.parseSingleMysqlStatement(s.getSql());
                MySqlInsertStatement sqlStatement1 = (MySqlInsertStatement) sqlStatement;
                SQLExprTableSource tableSource = sqlStatement1.getTableSource();
                return updateHandler(tableSource, sqlStatement1);
            }
        };
    }

    @NotNull
    private Iterator<TextUpdateInfo> updateHandler(SQLExprTableSource tableSource,  SQLStatement sqlStatement1) {
        Iterator<DataNode> iterator = backendTableInfos.iterator();
        return new Iterator<TextUpdateInfo>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public TextUpdateInfo next() {
                DataNode next = iterator.next();
                tableSource.setExpr(next.getTargetSchemaTable());

                return TextUpdateInfo.create(next.getTargetName(), Collections.singletonList(sqlStatement1.toString()));
            }
        };
    }

    @Override
    public Function<ParseContext, Iterator<TextUpdateInfo>> updateHandler() {
        return new Function<ParseContext, Iterator<TextUpdateInfo>>() {
            @Override
            public Iterator<TextUpdateInfo> apply(ParseContext s) {
                SQLStatement sqlStatement = SQLUtils.parseSingleMysqlStatement(s.getSql());
                MySqlUpdateStatement sqlStatement1 = (MySqlUpdateStatement) sqlStatement;
                SQLExprTableSource tableSource = (SQLExprTableSource)sqlStatement1.getTableSource();
                return updateHandler(tableSource, sqlStatement1);
            }
        };
    }

    @Override
    public Function<ParseContext, Iterator<TextUpdateInfo>> deleteHandler() {
        return new Function<ParseContext, Iterator<TextUpdateInfo>>() {
            @Override
            public Iterator<TextUpdateInfo> apply(ParseContext parseContext) {
                SQLStatement sqlStatement = SQLUtils.parseSingleMysqlStatement(parseContext.getSql());
                MySqlDeleteStatement sqlStatement1 = (MySqlDeleteStatement) sqlStatement;
                SQLExprTableSource tableSource = (SQLExprTableSource)sqlStatement1.getTableSource();
                return updateHandler(tableSource, sqlStatement1);
            }
        };
    }

    @Override
    public LogicTableType getType() {
        return LogicTableType.GLOBAL;
    }

    @Override
    public String getSchemaName() {
        return logicTable.getSchemaName();
    }

    @Override
    public String getTableName() {
        return logicTable.getTableName();
    }

    @Override
    public String getCreateTableSQL() {
        return logicTable.getCreateTableSQL();
    }

    @Override
    public List<SimpleColumnInfo> getColumns() {
        return logicTable.getRawColumns();
    }

    @Override
    public SimpleColumnInfo getColumnByName(String name) {
        return logicTable.getColumnByName(name);
    }

    @Override
    public SimpleColumnInfo getAutoIncrementColumn() {
        return logicTable.getAutoIncrementColumn();
    }

    @Override
    public String getUniqueName() {
        return logicTable.getUniqueName();
    }

    @Override
    public Supplier<String> nextSequence() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<DataNode> getGlobalDataNode() {
        return backendTableInfos;
    }
}