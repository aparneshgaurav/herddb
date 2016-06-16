/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package herddb.server;

import herddb.model.LimitedDataScanner;
import herddb.codec.RecordSerializer;
import herddb.core.stats.ConnectionsInfo;
import herddb.model.Column;
import herddb.model.DDLStatementExecutionResult;
import herddb.model.DMLStatementExecutionResult;
import herddb.model.DataScanner;
import herddb.model.DataScannerException;
import herddb.model.GetResult;
import herddb.model.NotLeaderException;
import herddb.model.ScanLimits;
import herddb.model.ScanResult;
import herddb.model.Statement;
import herddb.model.StatementEvaluationContext;
import herddb.model.StatementExecutionException;
import herddb.model.StatementExecutionResult;
import herddb.model.Table;
import herddb.model.TableAwareStatement;
import herddb.model.TransactionContext;
import herddb.model.TransactionResult;
import herddb.model.Tuple;
import herddb.model.commands.RollbackTransactionStatement;
import herddb.model.commands.ScanStatement;
import herddb.network.Channel;
import herddb.network.ChannelEventListener;
import herddb.network.Message;
import herddb.network.ServerSideConnection;
import herddb.security.sasl.SaslNettyServer;
import herddb.sql.TranslatedQuery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles a client Connection
 *
 * @author enrico.olivelli
 */
public class ServerSideConnectionPeer implements ServerSideConnection, ChannelEventListener {

    private static final Logger LOGGER = Logger.getLogger(ServerSideConnectionPeer.class.getName());
    private static final AtomicLong IDGENERATOR = new AtomicLong();
    private final long id = IDGENERATOR.incrementAndGet();
    private final Channel channel;
    private final Server server;
    /**
     * Open scanners. The ID is generated by the client
     */
    private final Map<String, ServerSideScannerPeer> scanners = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> openTransactions = new ConcurrentHashMap<>();
    private volatile boolean authenticated;
    private volatile SaslNettyServer saslNettyServer;
    private final String address;
    private volatile String username = "";
    private final long connectionTs = System.currentTimeMillis();

    public ServerSideConnectionPeer(Channel channel, Server server) {
        this.channel = channel;
        this.channel.setMessagesReceiver(this);
        this.server = server;
        this.address = channel.getRemoteAddress();
    }

    @Override
    public long getConnectionId() {
        return id;
    }
   
    @Override
    public void messageReceived(Message message, Channel _channel) {
        LOGGER.log(Level.FINEST, "messageReceived {0}", message);

        switch (message.type) {
            case Message.TYPE_SASL_TOKEN_MESSAGE_REQUEST: {
                try {
                    String mech = (String) message.parameters.get("mech");
                    if (saslNettyServer == null) {
                        saslNettyServer = new SaslNettyServer(server, mech);
                    }
                    byte[] responseToken = saslNettyServer.response(new byte[0]);
                    Message tokenChallenge = Message.SASL_TOKEN_SERVER_RESPONSE(responseToken);
                    _channel.sendReplyMessage(message, tokenChallenge);
                } catch (Exception err) {
                    Message error = Message.ERROR(null, err);
                    _channel.sendReplyMessage(message, error);
                }
                break;
            }
            case Message.TYPE_SASL_TOKEN_MESSAGE_TOKEN: {
                try {
                    if (saslNettyServer == null) {
                        Message error = Message.ERROR(null, new Exception("Authentication failed (SASL protocol error)"));
                        _channel.sendReplyMessage(message, error);
                        return;
                    }
                    byte[] token = (byte[]) message.parameters.get("token");
                    byte[] responseToken = saslNettyServer.response(token);
                    Message tokenChallenge = Message.SASL_TOKEN_SERVER_RESPONSE(responseToken);
                    if (saslNettyServer.isComplete()) {
                        username = saslNettyServer.getUserName();
                        authenticated = true;
                        LOGGER.severe("client "+channel+" completed SASL authentication");
                        saslNettyServer = null;
                    }
                    _channel.sendReplyMessage(message, tokenChallenge);                    
                } catch (Exception err) {
                    if (err instanceof javax.security.sasl.SaslException) {
                        LOGGER.log(Level.SEVERE, "SASL error " + err, err);
                        Message error = Message.ERROR(null, new Exception("Authentication failed (SASL error)"));
                        _channel.sendReplyMessage(message, error);
                    } else {
                        Message error = Message.ERROR(null, err);
                        _channel.sendReplyMessage(message, error);
                    }
                }
                break;
            }
            case Message.TYPE_EXECUTE_STATEMENT: {
                if (!authenticated) {
                    Message error = Message.ERROR(null, new Exception("autentication required (client "+channel+")"));
                    _channel.sendReplyMessage(message, error);
                    break;
                }
                Long tx = (Long) message.parameters.get("tx");
                long txId = tx != null ? tx : 0;
                String query = (String) message.parameters.get("query");
                String tableSpace = (String) message.parameters.get("tableSpace");
                List<Object> parameters = (List<Object>) message.parameters.get("params");
                try {
                    TransactionContext transactionContext = new TransactionContext(txId);
                    TranslatedQuery translatedQuery = server.getManager().getTranslator().translate(tableSpace, query, parameters, false, true);
                    Statement statement = translatedQuery.plan.mainStatement;
//                    LOGGER.log(Level.SEVERE, "query " + query + ", " + parameters + ", plan: " + translatedQuery.plan);
                    StatementExecutionResult result = server.getManager().executePlan(translatedQuery.plan, translatedQuery.context, transactionContext);
//                    LOGGER.log(Level.SEVERE, "query " + query + ", " + parameters + ", result:" + result);
                    if (result instanceof DMLStatementExecutionResult) {
                        DMLStatementExecutionResult dml = (DMLStatementExecutionResult) result;
                        Map<String, Object> otherData = null;
                        if (dml.getKey() != null) {
                            TableAwareStatement tableStatement = (TableAwareStatement) statement;
                            Table table = server.getManager().getTableSpaceManager(statement.getTableSpace()).getTableManager(tableStatement.getTable()).getTable();
                            Object key = RecordSerializer.deserializePrimaryKey(dml.getKey().data, table);
                            otherData = new HashMap<>();
                            otherData.put("key", key);
                        }
                        _channel.sendReplyMessage(message, Message.EXECUTE_STATEMENT_RESULT(dml.getUpdateCount(), otherData));
                    } else if (result instanceof GetResult) {
                        GetResult get = (GetResult) result;
                        if (!get.found()) {
                            _channel.sendReplyMessage(message, Message.EXECUTE_STATEMENT_RESULT(0, null));
                        } else {
                            Map<String, Object> record = RecordSerializer.toBean(get.getRecord(), get.getTable());
                            _channel.sendReplyMessage(message, Message.EXECUTE_STATEMENT_RESULT(1, record));
                        }
                    } else if (result instanceof TransactionResult) {
                        TransactionResult txresult = (TransactionResult) result;
                        Map<String, Object> data = new HashMap<>();
                        Set<Long> transactionsForTableSpace = openTransactions.get(statement.getTableSpace());
                        if (transactionsForTableSpace == null) {
                            transactionsForTableSpace = new ConcurrentSkipListSet<>();
                            openTransactions.put(statement.getTableSpace(), transactionsForTableSpace);
                        }
                        switch (txresult.getOutcome()) {
                            case BEGIN: {
                                transactionsForTableSpace.add(txresult.getTransactionId());
                                break;
                            }
                            case COMMIT:
                            case ROLLBACK:
                                transactionsForTableSpace.remove(txresult.getTransactionId());
                                break;
                        }
                        data.put("tx", txresult.getTransactionId());
                        _channel.sendReplyMessage(message, Message.EXECUTE_STATEMENT_RESULT(1, data));
                    } else if (result instanceof DDLStatementExecutionResult) {
                        DDLStatementExecutionResult ddl = (DDLStatementExecutionResult) result;
                        _channel.sendReplyMessage(message, Message.EXECUTE_STATEMENT_RESULT(1, null));
                    } else {
                        _channel.sendReplyMessage(message, Message.ERROR(null, new Exception("unknown result type " + result.getClass() + " (" + result + ")")));
                    }
                } catch (StatementExecutionException err) {
                    Message error = Message.ERROR(null, err);
                    if (err instanceof NotLeaderException) {
                        error.setParameter("notLeader", "true");
                    }
                    _channel.sendReplyMessage(message, error);
                }
            }
            break;
            case Message.TYPE_REQUEST_TABLESPACE_DUMP: {
                if (!authenticated) {
                    Message error = Message.ERROR(null, new Exception("autentication required (client "+channel+")"));
                    _channel.sendReplyMessage(message, error);
                    break;
                }
                String dumpId = (String) message.parameters.get("dumpId");
                int fetchSize = 10;
                if (message.parameters.containsKey("fetchSize")) {
                    fetchSize = (Integer) message.parameters.get("fetchSize");
                }
                String tableSpace = (String) message.parameters.get("tableSpace");
                server.getManager().dumpTableSpace(tableSpace, dumpId, message, _channel, fetchSize);

            }
            break;
            case Message.TYPE_OPENSCANNER: {
                if (!authenticated) {
                    Message error = Message.ERROR(null, new Exception("autentication required (client "+channel+")"));
                    _channel.sendReplyMessage(message, error);
                    break;
                }
                String tableSpace = (String) message.parameters.get("tableSpace");
                Long tx = (Long) message.parameters.get("tx");
                long txId = tx != null ? tx : 0;
                String query = (String) message.parameters.get("query");
                String scannerId = (String) message.parameters.get("scannerId");
                int fetchSize = 10;
                if (message.parameters.containsKey("fetchSize")) {
                    fetchSize = (Integer) message.parameters.get("fetchSize");
                }
                int maxRows = 0;
                if (message.parameters.containsKey("maxRows")) {
                    maxRows = (Integer) message.parameters.get("maxRows");
                }
                List<Object> parameters = (List<Object>) message.parameters.get("params");
                try {
                    TranslatedQuery translatedQuery = server.getManager().getTranslator().translate(tableSpace, query, parameters, true, true);
                    Statement statement = translatedQuery.plan.mainStatement;
                    TransactionContext transactionContext = new TransactionContext(txId);
                    if (statement instanceof ScanStatement) {

                        ScanResult scanResult = (ScanResult) server.getManager().executePlan(translatedQuery.plan, translatedQuery.context, transactionContext);
                        if (maxRows > 0) {
                            scanResult = new ScanResult(new LimitedDataScanner(scanResult.dataScanner, new ScanLimits(maxRows, 0)));
                        }
                        DataScanner dataScanner = scanResult.dataScanner;

                        ServerSideScannerPeer scanner = new ServerSideScannerPeer(dataScanner);
                        List<String> columns = new ArrayList<>();
                        for (Column c : dataScanner.getSchema()) {
                            columns.add(c.name);
                        }
                        List<Tuple> records = dataScanner.consume(fetchSize);
                        List<Map<String, Object>> converted = new ArrayList<>();
                        for (Tuple r : records) {
                            converted.add(r.toMap());
                        }
                        boolean last = dataScanner.isFinished();
                        LOGGER.log(Level.FINEST, "sending first {0} records to scanner {1} query {2}", new Object[]{converted.size(), scannerId, query});
                        if (!last) {
                            scanners.put(scannerId, scanner);
                        }
                        _channel.sendReplyMessage(message, Message.RESULTSET_CHUNK(null, scannerId, columns, converted, last));
                    } else {
                        _channel.sendReplyMessage(message, Message.ERROR(null, new Exception("unsupported query type for scan " + query + ": " + statement.getClass())));
                    }
                } catch (StatementExecutionException | DataScannerException err) {
                    LOGGER.log(Level.SEVERE, "error on scanner " + scannerId + ": " + err, err);
                    scanners.remove(scannerId);

                    Message error = Message.ERROR(null, err);
                    if (err instanceof NotLeaderException) {
                        error.setParameter("notLeader", "true");
                    }
                    _channel.sendReplyMessage(message, error);
                }

                break;
            }

            case Message.TYPE_FETCHSCANNERDATA: {
                if (!authenticated) {
                    Message error = Message.ERROR(null, new Exception("autentication required (client "+channel+")"));
                    _channel.sendReplyMessage(message, error);
                    break;
                }
                String scannerId = (String) message.parameters.get("scannerId");
                int fetchSize = (Integer) message.parameters.get("fetchSize");
                ServerSideScannerPeer scanner = scanners.get(scannerId);
                if (scanner != null) {
                    try {
                        DataScanner dataScanner = scanner.getScanner();
                        List<Tuple> records = dataScanner.consume(fetchSize);
                        List<String> columns = new ArrayList<>();
                        for (Column c : dataScanner.getSchema()) {
                            columns.add(c.name);
                        }
                        List<Map<String, Object>> converted = new ArrayList<>();
                        for (Tuple r : records) {
                            converted.add(r.toMap());
                        }
                        boolean last = false;
                        if (dataScanner.isFinished()) {
                            LOGGER.log(Level.FINEST, "unregistering scanner " + scannerId + ", resultset is finished");
                            scanners.remove(scannerId);
                            last = true;
                        }
//                        LOGGER.log(Level.SEVERE, "sending " + converted.size() + " records to scanner " + scannerId);
                        _channel.sendReplyMessage(message,
                                Message.RESULTSET_CHUNK(null, scannerId, columns, converted, last));
                    } catch (DataScannerException error) {
                        _channel.sendReplyMessage(message, Message.ERROR(null, error).setParameter("scannerId", scannerId));
                    }
                } else {
                    _channel.sendReplyMessage(message, Message.ERROR(null, new Exception("no such scanner " + scannerId + ", only " + scanners.keySet())).setParameter("scannerId", scannerId));
                }
            }
            ;
            break;

            case Message.TYPE_CLOSESCANNER: {
                if (!authenticated) {
                    Message error = Message.ERROR(null, new Exception("autentication required (client "+channel+")"));
                    _channel.sendReplyMessage(message, error);
                    break;
                }
                String scannerId = (String) message.parameters.get("scannerId");
                LOGGER.log(Level.SEVERE, "remove scanner " + scannerId + " as requested by client");
                ServerSideScannerPeer removed = scanners.remove(scannerId);
                if (removed != null) {
                    removed.clientClose();
                    _channel.sendReplyMessage(message, Message.ACK(null).setParameter("scannerId", scannerId));
                } else {
                    _channel.sendReplyMessage(message, Message.ERROR(null, new Exception("no such scanner " + scannerId)).setParameter("scannerId", scannerId));
                }

            }

            default:
                _channel.sendReplyMessage(message, Message.ERROR(null, new Exception("unsupported message type " + message.type)));
        }
    }

    @Override
    public void channelClosed(Channel channel) {
        LOGGER.log(Level.SEVERE, "channelClosed {0}", this);
        freeResources();
        this.server.connectionClosed(this);
    }

    private void freeResources() {
        for (Map.Entry<String, Set<Long>> openTransaction : openTransactions.entrySet()) {
            String tableSpace = openTransaction.getKey();
            for (Long tx : openTransaction.getValue()) {
                try {
                    LOGGER.log(Level.SEVERE, "rolling back trasaction tx=" + tx + " on tablespace " + tableSpace);
                    RollbackTransactionStatement statement = new RollbackTransactionStatement(tableSpace, tx);
                    StatementExecutionResult result = server.getManager().executeStatement(statement, StatementEvaluationContext.DEFAULT_EVALUATION_CONTEXT(), TransactionContext.NO_TRANSACTION);
                    LOGGER.log(Level.SEVERE, "rollback outcome trasaction tx=" + tx + " on tablespace " + tableSpace + ": " + result);
                } catch (Throwable t) {
                    LOGGER.log(Level.SEVERE, "error while rolling back trasaction tx=" + tx + " on tablespace " + tableSpace + " :" + t, t);
                }
            }
        }
        openTransactions.clear();
        LOGGER.log(Level.FINEST, "closing scanners " + scanners.keySet());
        scanners.values().forEach(s -> s.close());
        scanners.clear();
    }

    ConnectionsInfo.ConnectionInfo toConnectionInfo() {
        return new ConnectionsInfo.ConnectionInfo(id + "", connectionTs, username, address);
    }

}
