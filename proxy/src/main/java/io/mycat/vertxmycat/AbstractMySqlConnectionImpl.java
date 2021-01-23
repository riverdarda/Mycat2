package io.mycat.vertxmycat;

import io.mycat.MycatException;
import io.mycat.beans.mysql.MySQLCommandType;
import io.mycat.beans.mysql.packet.ErrorPacketImpl;
import io.mycat.proxy.callback.ResultSetCallBack;
import io.mycat.proxy.handler.backend.ResultSetHandler;
import io.mycat.proxy.reactor.NIOJob;
import io.mycat.proxy.reactor.ReactorEnvThread;
import io.mycat.proxy.session.MySQLClientSession;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.mysqlclient.MySQLConnection;
import io.vertx.mysqlclient.impl.protocol.ColumnDefinition;
import io.vertx.sqlclient.*;
import io.vertx.sqlclient.desc.ColumnDescriptor;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class AbstractMySqlConnectionImpl extends AbstractMySqlConnection {
    volatile Handler<Throwable> exceptionHandler;
    volatile Handler<Void> closeHandler;
    final MySQLClientSession mySQLClientSession;

    public AbstractMySqlConnectionImpl(MySQLClientSession mySQLClientSession) {
        this.mySQLClientSession = mySQLClientSession;
    }

    @Override
    public Future<PreparedStatement> prepare(String sql) {
        return null;
    }

    @Override
    public MySQLConnection exceptionHandler(Handler<Throwable> handler) {
        exceptionHandler = handler;
        return this;
    }

    @Override
    public MySQLConnection closeHandler(Handler<Void> handler) {
        closeHandler = handler;
        return this;
    }

    @Override
    public Future<Void> ping() {
        Promise<Void> promise = Promise.promise();
        ResultSetHandler.DEFAULT
                .request(mySQLClientSession, MySQLCommandType.COM_PING, new byte[]{},
                        commandResponse(promise));
        return promise.future();
    }


    @Override
    public Future<Void> specifySchema(String schemaName) {
        Promise<Void> promise = Promise.promise();
        ResultSetHandler.DEFAULT
                .request(mySQLClientSession, MySQLCommandType.COM_INIT_DB, schemaName.getBytes(StandardCharsets.UTF_8),
                        commandResponse(promise));
        return promise.future();
    }


    @Override
    public Future<Void> resetConnection() {
        Promise<Void> promise = Promise.promise();
        ResultSetHandler.DEFAULT
                .request(mySQLClientSession, MySQLCommandType.COM_RESET_CONNECTION, new byte[]{},
                        commandResponse(promise));
        return promise.future();
    }

    @Override
    public Query<RowSet<Row>> query(String sql) {
        return new Query<RowSet<Row>>() {
            ColumnDefinition[] columnDefinitionList;

            @Override
            public void execute(Handler<AsyncResult<RowSet<Row>>> handler) {
                Future<RowSet<Row>> future = execute();
                if (future != null) {
                    future.onComplete(handler);
                }
            }

            @Override
            public Future<RowSet<Row>> execute() {
                Consumer<ColumnDefinition[]> consumer = columnDefinitions -> columnDefinitionList = columnDefinitions;
                Promise<RowSet<Row>> rowSetPromise = runTextQuery(sql, mySQLClientSession, consumer,
                        (Collector)VertxRowSetImpl.collector(row -> row));
                return rowSetPromise.future();
            }

            @Override
            public <R> Query<SqlResult<R>> collecting(Collector<Row, ?, R> collectorArg) {
                return new Query<SqlResult<R>>() {
                    @Override
                    public void execute(Handler<AsyncResult<SqlResult<R>>> handler) {
                        Future<SqlResult<R>> future = execute();
                        if (future!=null){
                            future.onComplete(handler);
                        }
                    }

                    @Override
                    public Future<SqlResult<R>> execute() {
                        Consumer<ColumnDefinition[]> consumer = columnDefinitions -> columnDefinitionList = columnDefinitions;
                        Promise<VertxMycatTextCollector> promise = runTextQuery(sql, mySQLClientSession, consumer,
                                (Collector<Row, Object, Object>) collectorArg);
                        return promise.future().map(s->new SqlResult<R>() {
                            @Override
                            public int rowCount() {
                                return (int)s.getAffectedRows();
                            }

                            @Override
                            public List<String> columnsNames() {
                                return Arrays.stream(columnDefinitionList).map(i->i.name()).collect(Collectors.toList());
                            }

                            @Override
                            public List<ColumnDescriptor> columnDescriptors() {
                                return Arrays.asList(columnDefinitionList);
                            }

                            @Override
                            public int size() {
                                return (int) s.getRowCount();
                            }

                            @Override
                            public <V> V property(PropertyKind<V> propertyKind) {
                                if (propertyKind == MySQLClient.LAST_INSERTED_ID){
                                    Object lastInsertId = s.getLastInsertId();
                                    return (V)lastInsertId;
                                }
                                return null;
                            }

                            @Override
                            public R value() {
                                return (R)s.getRes();
                            }

                            @Override
                            public SqlResult<R> next() {
                                throw  new UnsupportedOperationException();
                            }
                        });
                    }

                    @Override
                    public <R> Query<SqlResult<R>> collecting(Collector<Row, ?, R> collector) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public <U> Query<RowSet<U>> mapping(Function<Row, U> mapper) {
                        throw new UnsupportedOperationException();
                    }
                };
            }

            @Override
            public <U> Query<RowSet<U>> mapping(Function<Row, U> mapper) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public PreparedQuery<RowSet<Row>> preparedQuery(String sql) {
        return new AbstractMySqlPreparedQuery() {

            @Override
            public Future<RowSet<Row>> execute(Tuple tuple) {
                return null;
            }

            @Override
            public Future<RowSet<Row>> executeBatch(List<Tuple> batch) {
                return null;
            }

            @Override
            public Future<RowSet<Row>> execute() {
                return null;
            }

            @Override
            public <R> PreparedQuery<SqlResult<R>> collecting(Collector<Row, ?, R> collector) {
                return null;
            }

            @Override
            public <U> PreparedQuery<RowSet<U>> mapping(Function<Row, U> mapper) {
                return null;
            }
        };
    }

    @NotNull
    private static Promise<VertxMycatTextCollector> runTextQuery(String curSql,
                                                                 MySQLClientSession mySQLClientSession,
                                                                 Consumer<ColumnDefinition[]> consumer,
                                                                 Collector<Row, Object, Object> collectorArg) {
        Promise<VertxMycatTextCollector> promise = Promise.promise();
        if (mySQLClientSession.getIOThread() == Thread.currentThread()) {
            VertxMycatTextCollector<Object, Object> resultSetHandler = new VertxMycatTextCollector<Object, Object>(consumer, (Collector) collectorArg);
            resultSetHandler.request(mySQLClientSession, MySQLCommandType.COM_QUERY, curSql.getBytes(),
                    new ResultSetCallBack<MySQLClientSession>() {
                        @Override
                        public void onFinishedSendException(Exception exception, Object sender,
                                                            Object attr) {
                            promise.fail(exception);
                        }

                        @Override
                        public void onFinishedException(Exception exception, Object sender, Object attr) {
                            promise.fail(exception);
                        }

                        @Override
                        public void onFinished(boolean monopolize, MySQLClientSession mysql,
                                               Object sender, Object attr) {
                            promise.complete((resultSetHandler));
                        }

                        @Override
                        public void onErrorPacket(ErrorPacketImpl errorPacket, boolean monopolize,
                                                  MySQLClientSession mysql, Object sender, Object attr) {
                            promise.fail(new MycatException(errorPacket.getErrorMessageString()));
                        }
                    });
        } else {
            mySQLClientSession.getIOThread().addNIOJob(new NIOJob() {
                @Override
                public void run(ReactorEnvThread reactor) throws Exception {
                    Promise<VertxMycatTextCollector> objectPromise = runTextQuery(curSql, mySQLClientSession, consumer, collectorArg);
                    objectPromise.handle(promise.future());
                }

                @Override
                public void stop(ReactorEnvThread reactor, Exception reason) {
                    promise.fail(reason);
                }

                @Override
                public String message() {
                    return "proxy query text result set";
                }
            });
        }
        return promise;
    }

    @Override
    public Future<Void> close() {
        Promise<Void> promise = Promise.promise();
        Thread thread = Thread.currentThread();
        if (thread instanceof ReactorEnvThread) {
            try {
                mySQLClientSession.close(true, "vertx mysql interface closed");
                if (closeHandler != null) {
                    try {
                        closeHandler.handle(null);
                    } catch (Throwable throwable) {
                        if (exceptionHandler != null) {
                            exceptionHandler.handle(throwable);
                        }
                    }
                }
                promise.complete();
            } catch (Throwable throwable) {
                promise.fail(throwable);
            }
        } else {
            mySQLClientSession.getIOThread()
                    .addNIOJob(new NIOJob() {
                        @Override
                        public void run(ReactorEnvThread reactor) throws Exception {
                            Future<Void> close = close();
                            close.onComplete(event -> promise.handle(event));
                        }

                        @Override
                        public void stop(ReactorEnvThread reactor, Exception reason) {
                            Future<Void> close = close();
                            close.onComplete(event -> promise.handle(event));
                        }

                        @Override
                        public String message() {
                            return "closing " + mySQLClientSession;
                        }
                    });
        }
        return promise.future();
    }

    @NotNull
    private ResultSetCallBack<MySQLClientSession> commandResponse(Promise<Void> promise) {
        return new ResultSetCallBack<MySQLClientSession>() {
            @Override
            public void onFinishedSendException(Exception exception, Object sender,
                                                Object attr) {
                promise.fail(exception);
            }

            @Override
            public void onFinishedException(Exception exception, Object sender, Object attr) {
                promise.fail(exception);
            }

            @Override
            public void onFinished(boolean monopolize, MySQLClientSession mysql, Object sender,
                                   Object attr) {
                promise.complete();
            }

            @Override
            public void onErrorPacket(ErrorPacketImpl errorPacket, boolean monopolize,
                                      MySQLClientSession mysql, Object sender, Object attr) {
                promise.fail(new MycatException(errorPacket.getErrorCode(), errorPacket.getErrorMessageString()));
            }
        };
    }
}
