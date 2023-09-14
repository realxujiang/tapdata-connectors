/*
 * Copyright 2016-2018 Leon Chen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.moilioncircle.redis.replicator;

import com.moilioncircle.redis.replicator.cmd.*;
import com.moilioncircle.redis.replicator.cmd.impl.SelectCommand;
import com.moilioncircle.redis.replicator.event.EventListener;
import com.moilioncircle.redis.replicator.event.PostCommandSyncEvent;
import com.moilioncircle.redis.replicator.event.PreCommandSyncEvent;
import com.moilioncircle.redis.replicator.io.AsyncBufferedInputStream;
import com.moilioncircle.redis.replicator.io.RateLimitInputStream;
import com.moilioncircle.redis.replicator.io.RedisInputStream;
import com.moilioncircle.redis.replicator.io.RedisOutputStream;
import com.moilioncircle.redis.replicator.net.RedisSocketFactory;
import com.moilioncircle.redis.replicator.rdb.RdbParser;
import com.moilioncircle.redis.replicator.util.Strings;
import com.moilioncircle.redis.replicator.util.XScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.moilioncircle.redis.replicator.Constants.DOLLAR;
import static com.moilioncircle.redis.replicator.Constants.STAR;
import static com.moilioncircle.redis.replicator.RedisSocketReplicator.SyncMode.*;
import static com.moilioncircle.redis.replicator.Status.*;
import static com.moilioncircle.redis.replicator.cmd.CommandParsers.toInt;
import static com.moilioncircle.redis.replicator.util.Strings.format;
import static com.moilioncircle.redis.replicator.util.Strings.isEquals;
import static com.moilioncircle.redis.replicator.util.Tuples.of;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Leon Chen
 * @since 2.1.0
 */
public class RedisSocketReplicator extends AbstractReplicator {

    protected static final Logger logger = LoggerFactory.getLogger(RedisSocketReplicator.class);

    protected int db = -1;
    protected Socket socket;
    protected ReplyParser replyParser;
    protected ScheduledFuture<?> heartbeat;
    protected RedisOutputStream outputStream;
    protected XScheduledExecutorService executor;

    //
    protected final int port;
    protected final String host;
    protected final ReplFilter[] replFilters;
    protected final RedisSocketFactory socketFactory;
    protected final AtomicBoolean manual = new AtomicBoolean(false);

    public RedisSocketReplicator(String host, int port, Configuration configuration) {
        Objects.requireNonNull(host);
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("illegal argument port: " + port);
        Objects.requireNonNull(configuration);
        this.host = host;
        this.port = port;
        this.configuration = configuration;
        this.socketFactory = new RedisSocketFactory(configuration);
        builtInCommandParserRegister();
        if (configuration.isUseDefaultExceptionListener())
            addExceptionListener(new DefaultExceptionListener());
        this.replFilters = configuration.getReplFilters();
        if (this.replFilters != null) {
            for (int i = 0; i < replFilters.length; i++) {
                replFilters[i] = new InitializedReplFilter(replFilters[i], this);
            }
        }
    }

    public String getHost() {
        return this.host;
    }

    public int getPort() {
        return this.port;
    }

    /**
     * PSYNC
     * <p>
     *
     * @throws IOException when read timeout or connect timeout
     */
    @Override
    public void open() throws IOException {
        manual.compareAndSet(true, false);
        this.executor = new XScheduledExecutorService(configuration);
        try {
            new RedisSocketReplicatorRetrier().retry(this);
        } finally {
            doClose();
            doCloseListener(this);
            this.executor.terminateQuietly(configuration.getConnectionTimeout(), MILLISECONDS);
        }
    }

    @Override
    public void close() throws IOException {
        super.close();
        manual.compareAndSet(false, true);
    }

    protected SyncMode trySync(final String reply) throws IOException {
        logger.info(reply);
        if (reply.startsWith("FULLRESYNC")) {
            // reset db
            this.db = -1;
            parseDump(this);
            String[] ary = reply.split(" ");
            configuration.setReplId(ary[1]);
            configuration.setReplOffset(Long.parseLong(ary[2]));
            return PSYNC;
        } else if (reply.startsWith("CONTINUE")) {
            String[] ary = reply.split(" ");
            // redis-4.0 compatible
            String replId = configuration.getReplId();
            if (ary.length > 1 && replId != null && !replId.equals(ary[1])) configuration.setReplId(ary[1]);
            return PSYNC;
        } else if (reply.startsWith("NOMASTERLINK") || reply.startsWith("LOADING")) {
            return SYNC_LATER;
        } else {
            logger.info("SYNC");
            send("SYNC".getBytes());
            // reset db
            this.db = -1;
            parseDump(this);
            return SYNC;
        }
    }

    protected void parseDump(final AbstractReplicator replicator) throws IOException {
        byte[] rawReply = reply(new BulkReplyHandler() {
            @Override
            public byte[] handle(long len, RedisInputStream in) throws IOException {
                if (len != -1) {
                    logger.info("RDB dump file size:{}", len);
                } else {
                    logger.info("Disk-less replication.");
                }
                if (len != -1 && configuration.isDiscardRdbEvent()) {
                    logger.info("discard {} bytes", len);
                    in.skip(len);
                } else {
                    new RdbParser(in, replicator).parse();
                    // skip 40 bytes delimiter when disk-less replication
                    if (len == -1) in.skip(40, false);
                }
                return "OK".getBytes();
            }
        });
        String reply = Strings.toString(rawReply);
        if (Objects.equals(reply, "OK")) return;
        throw new IOException("SYNC failed. reason : [" + reply + "]");
    }

    protected void establishConnection() throws IOException {
        connect();
        if (configuration.getAuthPassword() != null) auth(configuration.getAuthUser(), configuration.getAuthPassword());
        sendPing();
        sendSlavePort();
        sendSlaveIp();
        sendSlaveCapa("eof");
        sendSlaveCapa("psync2");
        if (this.replFilters != null) {
            for (ReplFilter filter : this.replFilters) {
                sendSlaveFilter(filter);
            }
        }
    }

    protected void auth(String user, String password) throws IOException {
        if (password != null) {
            // sha256 mask password
            String mask = "#" + Strings.mask(password);
            if (user == null) {
                logger.info("AUTH {}", mask);
                send("AUTH".getBytes(), password.getBytes());
            } else {
                logger.info("AUTH {} {}", user, mask);
                send("AUTH".getBytes(), user.getBytes(), password.getBytes());
            }
            final String reply = Strings.toString(reply());
            logger.info(reply);
            if (Objects.equals(reply, "OK")) return;
            if (reply.contains("no password")) {
                if (user == null) {
                    logger.warn("[AUTH {}] failed. {}", mask, reply);
                } else {
                    logger.warn("[AUTH {} {}] failed. {}", user, mask, reply);
                }
                return;
            }
            if (user == null) {
                throw new AssertionError("[AUTH " + mask + "] failed. " + reply);
            } else {
                throw new AssertionError("[AUTH " + user + " " + mask + "] failed. " + reply);
            }
        }
    }

    protected void sendPing() throws IOException {
        logger.info("PING");
        send("PING".getBytes());
        final String reply = Strings.toString(reply());
        logger.info(reply);
        if ("PONG".equalsIgnoreCase(reply)) return;
        if (reply.contains("NOAUTH")) throw new AssertionError(reply);
        if (reply.contains("NOPERM")) throw new AssertionError(reply);
        if (reply.contains("operation not permitted")) throw new AssertionError("-NOAUTH Authentication required.");
        logger.warn("[PING] failed. {}", reply);
    }

    protected void sendSlavePort() throws IOException {
        // REPLCONF listening-prot ${port}
        int slavePort = this.configuration.getSlavePort();
        if (slavePort == 0) {
            slavePort = socket.getLocalPort();
        }
        logger.info("REPLCONF listening-port {}", slavePort);
        send("REPLCONF".getBytes(), "listening-port".getBytes(), String.valueOf(slavePort).getBytes());
        final String reply = Strings.toString(reply());
        logger.info(reply);
        if (Objects.equals(reply, "OK")) return;
        logger.warn("[REPLCONF listening-port {}] failed. {}", socket.getLocalPort(), reply);
    }

    protected void sendSlaveIp() throws IOException {
        // REPLCONF ip-address ${address}
        logger.info("REPLCONF ip-address {}", socket.getLocalAddress().getHostAddress());
        send("REPLCONF".getBytes(), "ip-address".getBytes(), socket.getLocalAddress().getHostAddress().getBytes());
        final String reply = Strings.toString(reply());
        logger.info(reply);
        if (Objects.equals(reply, "OK")) return;
        //redis 3.2+
        logger.warn("[REPLCONF ip-address {}] failed. {}", socket.getLocalAddress().getHostAddress(), reply);
    }

    protected void sendSlaveCapa(String cmd) throws IOException {
        // REPLCONF capa eof
        logger.info("REPLCONF capa {}", cmd);
        send("REPLCONF".getBytes(), "capa".getBytes(), cmd.getBytes());
        final String reply = Strings.toString(reply());
        logger.info(reply);
        if (Objects.equals(reply, "OK")) return;
        logger.warn("[REPLCONF capa {}] failed. {}", cmd, reply);
    }

    protected void sendSlaveFilter(ReplFilter filter) throws IOException {
        String[] command = filter.command();
        String info = String.join(" ", command);
        logger.info(info);
        byte[][] args = new byte[command.length - 1][];
        for (int i = 1, j = 0; i < command.length; i++) {
            args[j++] = command[i].getBytes();
        }
        send(command[0].getBytes(), args);
        final String reply = Strings.toString(reply());
        logger.info(reply);
        if (Objects.equals(reply, "OK")) {
            EventListener listener = filter.listener(this);
            if (listener != null) {
                this.removeEventListener(listener);
                this.addEventListener(listener);
            }
            return;
        }
        logger.warn("[{}] failed. {}", info, reply);
    }

    protected void heartbeat() {
        assert heartbeat == null || heartbeat.isCancelled();
        heartbeat = executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                sendQuietly("REPLCONF".getBytes(), "ACK".getBytes(), String.valueOf(configuration.getReplOffset()).getBytes());
            }
        }, configuration.getHeartbeatPeriod(), configuration.getHeartbeatPeriod(), MILLISECONDS);
        logger.info("heartbeat started.");
    }

    protected void send(byte[] command) throws IOException {
        send(command, new byte[0][]);
    }

    protected void send(byte[] command, final byte[]... args) throws IOException {
        outputStream.write(STAR);
        outputStream.write(String.valueOf(args.length + 1).getBytes());
        outputStream.writeCrLf();
        outputStream.write(DOLLAR);
        outputStream.write(String.valueOf(command.length).getBytes());
        outputStream.writeCrLf();
        outputStream.write(command);
        outputStream.writeCrLf();
        for (final byte[] arg : args) {
            outputStream.write(DOLLAR);
            outputStream.write(String.valueOf(arg.length).getBytes());
            outputStream.writeCrLf();
            outputStream.write(arg);
            outputStream.writeCrLf();
        }
        outputStream.flush();
    }

    protected void sendQuietly(byte[] command, final byte[]... args) {
        try {
            send(command, args);
        } catch (IOException e) {
            // NOP
        }
    }

    @SuppressWarnings("unchecked")
    protected <T> T reply() throws IOException {
        return (T) replyParser.parse();
    }

    @SuppressWarnings("unchecked")
    protected <T> T reply(BulkReplyHandler handler) throws IOException {
        return (T) replyParser.parse(handler);
    }

    protected void connect() throws IOException {
        if (!compareAndSet(DISCONNECTED, CONNECTING)) return;
        try {
            socket = socketFactory.createSocket(host, port, configuration.getConnectionTimeout());
            outputStream = new RedisOutputStream(socket.getOutputStream());
            InputStream inputStream = socket.getInputStream();
            if (configuration.getAsyncCachedBytes() > 0) {
                inputStream = new AsyncBufferedInputStream(inputStream, configuration.getAsyncCachedBytes());
            }
            if (configuration.getRateLimit() > 0) {
                inputStream = new RateLimitInputStream(inputStream, configuration.getRateLimit());
            }
            this.inputStream = new RedisInputStream(inputStream, configuration.getBufferSize());
            this.inputStream.setRawByteListeners(this.rawByteListeners);
            replyParser = new ReplyParser(this.inputStream, new RedisCodec());
            logger.info("Connected to redis-server[{}:{}]", host, port);
        } finally {
            setStatus(CONNECTED);
        }
    }

    @Override
    protected void doClose() throws IOException {
        compareAndSet(CONNECTED, DISCONNECTING);

        try {
            if (heartbeat != null) {
                if (!heartbeat.isCancelled()) heartbeat.cancel(true);
                logger.info("heartbeat canceled.");
            }

            try {
                if (inputStream != null) {
                    inputStream.setRawByteListeners(null);
                    inputStream.close();
                }
            } catch (IOException e) {
                // NOP
            }
            try {
                if (outputStream != null) outputStream.close();
            } catch (IOException e) {
                // NOP
            }
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) {
                // NOP
            }
            logger.info("socket closed. redis-server[{}:{}]", host, port);
        } finally {
            setStatus(DISCONNECTED);
        }
    }

    protected enum SyncMode {SYNC, PSYNC, SYNC_LATER}

    private class RedisSocketReplicatorRetrier extends AbstractReplicatorRetrier {

        @Override
        protected boolean connect() throws IOException {
            establishConnection();
            return true;
        }

        @Override
        protected boolean close(IOException reason) throws IOException {
            if (reason != null)
                logger.error("[redis-replicator] socket error. redis-server[{}:{}]", host, port, reason);
            doClose();
            if (reason != null)
                logger.info("reconnecting to redis-server[{}:{}]. retry times:{}", host, port, (retries + 1));
            return true;
        }

        @Override
        protected boolean isManualClosed() {
            return manual.get();
        }

        @Override
        protected boolean open() throws IOException {
            String replId = configuration.getReplId();
            long replOffset = configuration.getReplOffset();
            logger.info("PSYNC {} {}", replId, String.valueOf(replOffset >= 0 ? replOffset + 1 : replOffset));
            send("PSYNC".getBytes(), replId.getBytes(), String.valueOf(replOffset >= 0 ? replOffset + 1 : replOffset).getBytes());
            final String reply = Strings.toString(reply());

            SyncMode mode = trySync(reply);
            if (mode == PSYNC && getStatus() == CONNECTED) {
                heartbeat();
            } else if (mode == SYNC_LATER && getStatus() == CONNECTED) {
                return false;
            }
            if (getStatus() != CONNECTED) return true;
            submitEvent(new PreCommandSyncEvent());
            if (db != -1) {
                submitEvent(new SelectCommand(db));
            }
            final long[] offset = new long[1];
            while (getStatus() == CONNECTED) {
                Object obj = replyParser.parse(len -> offset[0] = len);
                if (obj instanceof Object[]) {
                    if (verbose() && logger.isDebugEnabled())
                        logger.debug(format((Object[]) obj));
                    Object[] raw = (Object[]) obj;
                    CommandName name = CommandName.name(Strings.toString(raw[0]));
                    final CommandParser<? extends Command> parser;
                    if ((parser = commands.get(name)) == null) {
                        logger.warn("command [{}] not register. raw command:{}", name, format(raw));
                        configuration.addOffset(offset[0]);
                        offset[0] = 0L;
                        continue;
                    }
                    final long st = configuration.getReplOffset();
                    final long ed = st + offset[0];
                    if (isEquals(Strings.toString(raw[0]), "SELECT")) {
                        db = toInt(raw[1]);
                        submitEvent(parser.parse(raw), of(st, ed));
                    } else if (isEquals(Strings.toString(raw[0]), "REPLCONF") && isEquals(Strings.toString(raw[1]), "GETACK")) {
                        if (mode == PSYNC) executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                sendQuietly("REPLCONF".getBytes(), "ACK".getBytes(), String.valueOf(configuration.getReplOffset()).getBytes());
                            }
                        });
                    } else {
                        // include ping command
                        submitEvent(parser.parse(raw), of(st, ed));
                    }
                } else {
                    logger.warn("unexpected redis reply:{}", obj);
                }
                configuration.addOffset(offset[0]);
                offset[0] = 0L;
            }
            if (getStatus() == CONNECTED) {
                // should not reach here. add this line for code idempotent.
                submitEvent(new PostCommandSyncEvent());
            }
            return true;
        }
    }
}
