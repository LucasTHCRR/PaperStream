package net.paperstream.link;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.MinecraftServer;
import net.paperstream.PaperStreamConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.slf4j.Logger;

/**
 * The link from this server to PaperProxy. Pairing once with a code from the proxy sets up
 * Velocity forwarding by itself. Afterwards the link lets the proxy move players away before
 * a stop, run console commands and restart this server, all on an encrypted connection.
 */
public final class ProxyLink {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final Path FILE = Path.of("paperstream-link.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final long PROXY_SILENCE_MILLIS = 35_000L;
    private static volatile boolean proxyAnswersPings;

    private static volatile Stored stored;
    private static volatile Thread thread;
    private static volatile Connection connection;
    private static volatile CountDownLatch moved;
    private static volatile boolean started;
    private static volatile boolean stopAnnounced;
    private static final java.util.concurrent.atomic.AtomicBoolean PAIRING = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile String lastProblem = "";

    private record Stored(String host, int port, String server, byte[] key, String secret) {
    }

    private record Connection(Socket socket, OutputStream out, LinkProtocol.Session session) {
    }

    private ProxyLink() {
    }

    /**
     * Called from PaperStream#init before the network starts: a paired server gets its
     * forwarding settings back.
     */
    public static void init(final MinecraftServer server) {
        if (!PaperStreamConfig.proxyLink || !Files.exists(FILE)) {
            return;
        }
        try {
            final JsonObject json = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            stored = new Stored(json.get("proxy-host").getAsString(), json.get("proxy-port").getAsInt(),
                json.get("server").getAsString(), LinkProtocol.unb64(json.get("key").getAsString()),
                json.get("secret").getAsString());
            applyForwarding(server, stored.secret());
            LOGGER.info("PaperStream: linked to PaperProxy at {}:{} as {}", stored.host(), stored.port(), stored.server());
        } catch (final IOException | RuntimeException ex) {
            LOGGER.error("PaperStream: could not read {}, the proxy link is off", FILE, ex);
        }
    }

    /**
     * Called every tick; opens the link once the server has started.
     */
    public static void tick() {
        if (!started) {
            started = true;
            startThread();
        }
    }

    private static void applyForwarding(final MinecraftServer server, final String secret) {
        if (!PaperStreamConfig.proxyConfigureForwarding) {
            if (!GlobalConfiguration.get().proxies.velocity.enabled) {
                LOGGER.warn("PaperStream: proxy.configure-forwarding is off, so forwarding stays as it is. Players can only join "
                    + "through the proxy once you set proxies.velocity.enabled: true and the proxy's forwarding secret in "
                    + "config/paper-global.yml, and online-mode=false in server.properties (or turn proxy.configure-forwarding on).");
            }
            return;
        }
        final var velocity = GlobalConfiguration.get().proxies.velocity;
        velocity.secret = secret;
        velocity.enabled = true;
        server.setUsesAuthentication(false);
    }

    /**
     * Whether forwarding was set up by hand, then pairing never touches it.
     */
    public static boolean configuredByHand() {
        return stored == null && (GlobalConfiguration.get().proxies.velocity.enabled
            || org.spigotmc.SpigotConfig.bungee);
    }

    public static boolean isPaired() {
        return stored != null;
    }

    public static boolean isConnected() {
        return connection != null;
    }

    private static synchronized void startThread() {
        if (stored == null || (thread != null && thread.isAlive())) {
            return;
        }
        thread = Thread.ofPlatform().daemon().name("PaperStream Proxy Link").start(ProxyLink::run);
    }

    private static void run() {
        long backoff = 2_000L;
        while (stored != null && !Thread.currentThread().isInterrupted()) {
            final Stored target = stored;
            if (target == null) {
                return;
            }
            try {
                connect(target);
                backoff = 2_000L;
            } catch (final IOException | GeneralSecurityException | RuntimeException ex) {
                if (!sameLink(stored, target)) {
                    return; // reset or paired again on purpose
                }
                if (ex instanceof RefusedException refused && refused.permanent) {
                    // The proxy will not accept this key again; trying often only fills both logs.
                    backoff = 300_000L;
                }
                problem("PaperStream: proxy link to " + target.host() + ":" + target.port()
                    + " failed: " + (ex instanceof java.io.EOFException ? "the proxy closed the connection"
                    : ex.getMessage() != null ? ex.getMessage() : ex.toString()));
            } finally {
                connection = null;
            }
            try {
                Thread.sleep(backoff);
            } catch (final InterruptedException ex) {
                return;
            }
            backoff = Math.min(backoff * 2, Math.max(backoff, 15_000L));
        }
    }

    /** Logs a problem once until it changes, so a missing proxy does not flood the log. */
    /** The same pairing, even if the forwarding secret was updated since. */
    private static boolean sameLink(final Stored current, final Stored target) {
        return current != null && current.server().equals(target.server())
            && java.util.Arrays.equals(current.key(), target.key());
    }

    private static void problem(final String message) {
        if (!message.equals(lastProblem)) {
            lastProblem = message;
            LOGGER.warn(message);
        }
    }

    private static Socket open(final String host, final int port) throws IOException {
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5_000);
        socket.setSoTimeout(15_000);
        socket.setTcpNoDelay(true);
        socket.getOutputStream().write(LinkProtocol.handshake(host, port));
        final JsonObject hello = read(socket.getInputStream());
        if (!"hello".equals(op(hello))) {
            socket.close();
            throw new IOException("this is not a PaperProxy with PaperStream link support");
        }
        return socket;
    }

    private static JsonObject read(final InputStream in) throws IOException {
        return JsonParser.parseString(new String(LinkProtocol.readFrame(in), StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    private static void write(final OutputStream out, final JsonObject message) throws IOException {
        LinkProtocol.writeFrame(out, message.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String op(final JsonObject message) {
        return message.has("op") ? message.get("op").getAsString() : "";
    }

    private static final class RefusedException extends IOException {
        private final boolean permanent;

        private RefusedException(final String message, final boolean permanent) {
            super(message);
            this.permanent = permanent;
        }
    }

    private static IOException refused(final JsonObject message) {
        final String reason = message.has("reason") ? message.get("reason").getAsString() : "?";
        return new RefusedException(switch (reason) {
            case "no-pairing" -> "the proxy has no open pairing code, run /paperproxy link pair <name> on the proxy first";
            case "wrong-code" -> "wrong pairing code";
            case "unknown", "auth" -> "the proxy does not know this server any more. Run /paperstream proxy reset and pair again";
            case "no-secret" -> "the forwarding secret of the proxy is empty, put one into its forwarding.secret file";
            case "version" -> "PaperStream and PaperProxy use different link versions, update both";
            default -> "the proxy refused the link (" + reason + ")";
        }, reason.equals("unknown") || reason.equals("auth"));
    }

    /**
     * Pairs with a proxy. Blocks, call it off the main thread.
     *
     * @return the server name the proxy gave this server
     */
    public static String pair(final MinecraftServer server, final String address, final String code)
        throws IOException, GeneralSecurityException {
        if (!PAIRING.compareAndSet(false, true)) {
            throw new IOException("a pairing is already running");
        }
        try {
            return pairNow(server, address, code);
        } finally {
            PAIRING.set(false);
        }
    }

    private static String pairNow(final MinecraftServer server, final String address, final String code)
        throws IOException, GeneralSecurityException {
        final int colon = address.lastIndexOf(':');
        final boolean hasPort = colon > 0 && address.indexOf(':') == colon
            || colon > 0 && address.startsWith("[") && address.charAt(colon - 1) == ']';
        String host = hasPort ? address.substring(0, colon) : address;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        final int port;
        try {
            port = hasPort ? Integer.parseInt(address.substring(colon + 1)) : 25577;
        } catch (final NumberFormatException ex) {
            throw new IOException("invalid port in " + address);
        }
        try (Socket socket = open(host, port)) {
            final InputStream in = socket.getInputStream();
            final OutputStream out = socket.getOutputStream();
            final KeyPair keyPair = LinkProtocol.newKeyPair();
            final byte[] clientNonce = LinkProtocol.random(32);
            final JsonObject pair = new JsonObject();
            pair.addProperty("op", "pair");
            pair.addProperty("v", LinkProtocol.VERSION);
            pair.addProperty("pub", LinkProtocol.b64(keyPair.getPublic().getEncoded()));
            pair.addProperty("nonce", LinkProtocol.b64(clientNonce));
            pair.addProperty("port", server.getPort());
            write(out, pair);

            final JsonObject challenge = read(in);
            if (!"pair-challenge".equals(op(challenge))) {
                throw refused(challenge);
            }
            final byte[] proxyPublic = LinkProtocol.unb64(challenge.get("pub").getAsString());
            final byte[] transcript = LinkProtocol.join(keyPair.getPublic().getEncoded(), clientNonce,
                proxyPublic, LinkProtocol.unb64(challenge.get("nonce").getAsString()));
            final byte[] codeBytes = LinkProtocol.codeBytes(code);
            final JsonObject proof = new JsonObject();
            proof.addProperty("op", "pair-proof");
            proof.addProperty("proof", LinkProtocol.b64(LinkProtocol.mac(codeBytes, "backend", transcript)));
            write(out, proof);

            final JsonObject ok = read(in);
            if (!"pair-ok".equals(op(ok))) {
                throw refused(ok);
            }
            if (!LinkProtocol.same(LinkProtocol.unb64(ok.get("proof").getAsString()),
                LinkProtocol.mac(codeBytes, "proxy", transcript))) {
                throw new GeneralSecurityException("the other side does not know the code, it is not your proxy");
            }
            final LinkProtocol.Session session = LinkProtocol.pairSession(
                LinkProtocol.agree(keyPair.getPrivate(), proxyPublic), code, transcript, true);
            final JsonObject result = JsonParser.parseString(new String(
                session.open(LinkProtocol.readFrame(in)), StandardCharsets.UTF_8)).getAsJsonObject();

            if (result.get("secret").getAsString().isBlank()) {
                throw new IOException("the forwarding secret of the proxy is empty, put one into its forwarding.secret file");
            }
            final Stored paired = new Stored(host, port, result.get("server").getAsString(),
                LinkProtocol.unb64(result.get("key").getAsString()), result.get("secret").getAsString());
            save(paired);
            final JsonObject confirm = new JsonObject();
            confirm.addProperty("op", "paired");
            LinkProtocol.writeFrame(out, session.seal(confirm.toString().getBytes(StandardCharsets.UTF_8)));
            // The proxy closes the connection once it saved the pairing; linking before that
            // would find this server still unknown.
            try {
                LinkProtocol.readFrame(in);
            } catch (final IOException expected) {
                // closed, as it should be
            }
            stored = paired;
            lastProblem = "";
            applyForwarding(server, paired.secret());
            LOGGER.info("PaperStream: paired with PaperProxy at {}:{} as {}.{}", host, port, paired.server(),
                PaperStreamConfig.proxyConfigureForwarding ? " Velocity forwarding is on, players now join through the proxy." : "");
            startThread();
            return paired.server();
        }
    }

    private static void save(final Stored link) throws IOException {
        final JsonObject json = new JsonObject();
        json.addProperty("proxy-host", link.host());
        json.addProperty("proxy-port", link.port());
        json.addProperty("server", link.server());
        json.addProperty("key", LinkProtocol.b64(link.key()));
        json.addProperty("secret", link.secret());
        final Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(json));
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        } catch (final UnsupportedOperationException ignored) {
            // Windows
        }
        Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Forgets the pairing and turns Velocity forwarding off again.
     */
    public static void reset(final MinecraftServer server) {
        stored = null;
        final Thread running = thread;
        if (running != null) {
            running.interrupt();
        }
        final Connection current = connection;
        if (current != null) {
            try {
                current.socket().close();
            } catch (final IOException ignored) {
                // closing anyway
            }
        }
        try {
            Files.deleteIfExists(FILE);
        } catch (final IOException ex) {
            LOGGER.error("PaperStream: could not delete {}", FILE, ex);
        }
        if (PaperStreamConfig.proxyConfigureForwarding) {
            GlobalConfiguration.get().proxies.velocity.enabled = false;
            GlobalConfiguration.get().proxies.velocity.secret = "";
            server.setUsesAuthentication(((net.minecraft.server.dedicated.DedicatedServer) server).getProperties().onlineMode);
            LOGGER.info("PaperStream: proxy link removed, Velocity forwarding is off again");
        } else {
            LOGGER.info("PaperStream: proxy link removed, forwarding settings were not touched");
        }
    }

    public static List<String> info() {
        final List<String> lines = new ArrayList<>();
        final Stored link = stored;
        if (!PaperStreamConfig.proxyLink) {
            lines.add("Proxy link: off (proxy.link in paperstream.yml)");
        } else if (link == null) {
            lines.add(configuredByHand()
                ? "Proxy link: not paired, forwarding is set up by hand and stays untouched"
                : "Proxy link: not paired. On the proxy run /paperproxy link pair <name>, then here /paperstream pair <proxy-address> <code>");
        } else {
            lines.add("Proxy link: paired with " + link.host() + ":" + link.port() + " as " + link.server()
                + ", " + (connection != null ? "connected" : "not connected"));
            if (connection == null && !lastProblem.isEmpty()) {
                lines.add("Last problem: " + lastProblem.replace("PaperStream: ", ""));
            }
            final java.util.List<String> allowed = PaperStreamConfig.proxyExecAllowed;
            lines.add("Stop announcement: " + (PaperStreamConfig.proxyAnnounceShutdown ? "on" : "off")
                + ", restart by the proxy: " + (PaperStreamConfig.proxyAllowRestart ? "allowed" : "not allowed")
                + ", forwarding set up by PaperStream: " + (PaperStreamConfig.proxyConfigureForwarding ? "yes" : "no"));
            lines.add("Commands the proxy may run here: " + (allowed.contains("*") ? "all"
                : allowed.isEmpty() ? "none" : String.join(", ", allowed)));
        }
        return lines;
    }

    private static void connect(final Stored target) throws IOException, GeneralSecurityException {
        try (Socket socket = open(target.host(), target.port())) {
            final InputStream in = socket.getInputStream();
            final OutputStream out = socket.getOutputStream();
            final byte[] name = target.server().getBytes(StandardCharsets.UTF_8);
            final byte[] clientNonce = LinkProtocol.random(32);
            final JsonObject link = new JsonObject();
            link.addProperty("op", "link");
            link.addProperty("v", LinkProtocol.VERSION);
            link.addProperty("server", target.server());
            link.addProperty("nonce", LinkProtocol.b64(clientNonce));
            write(out, link);

            final JsonObject challenge = read(in);
            if (!"challenge".equals(op(challenge))) {
                throw refused(challenge);
            }
            final byte[] serverNonce = LinkProtocol.unb64(challenge.get("nonce").getAsString());
            final JsonObject auth = new JsonObject();
            auth.addProperty("op", "auth");
            auth.addProperty("mac", LinkProtocol.b64(LinkProtocol.mac(target.key(), "backend", name,
                clientNonce, serverNonce)));
            write(out, auth);
            final JsonObject ok = read(in);
            if (!"ok".equals(op(ok))) {
                throw refused(ok);
            }
            if (!LinkProtocol.same(LinkProtocol.unb64(ok.get("mac").getAsString()),
                LinkProtocol.mac(target.key(), "proxy", name, clientNonce, serverNonce))) {
                throw new GeneralSecurityException("the proxy could not prove the link key");
            }
            final Connection current = new Connection(socket,
                out, LinkProtocol.linkSession(target.key(), target.server(), clientNonce, serverNonce, true));
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            connection = current;
            LOGGER.info("PaperStream: proxy link to {}:{} is up", target.host(), target.port());
            lastProblem = "";
            final JsonObject ready = new JsonObject();
            ready.addProperty("op", "ready");
            ready.addProperty("players", Bukkit.getOnlinePlayers().size());
            send(ready);
            long lastHeard = System.currentTimeMillis();
            proxyAnswersPings = false;
            while (sameLink(stored, target)) {
                final byte[] frame;
                try {
                    frame = LinkProtocol.readFrame(in);
                } catch (final SocketTimeoutException timeout) {
                    // a proxy that hangs keeps the socket open, so silence is the only sign
                    // older PaperProxy versions never answer pings, only watch proxies that do
                    if (proxyAnswersPings && System.currentTimeMillis() - lastHeard > PROXY_SILENCE_MILLIS) {
                        throw new IOException("the proxy stopped answering");
                    }
                    final JsonObject ping = new JsonObject();
                    ping.addProperty("op", "ping");
                    send(ping);
                    continue;
                }
                lastHeard = System.currentTimeMillis();
                handle(JsonParser.parseString(new String(current.session().open(frame),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            }
        }
    }

    private static void send(final JsonObject message) throws IOException {
        final Connection current = connection;
        if (current == null) {
            throw new IOException("not connected");
        }
        synchronized (current) {
            LinkProtocol.writeFrame(current.out(), current.session().seal(
                message.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static void handle(final JsonObject message) {
        final MinecraftServer server = MinecraftServer.getServer();
        switch (op(message)) {
            case "exec" -> {
                final int id = message.get("id").getAsInt();
                final String command = message.get("command").getAsString();
                if (!execAllowed(command)) {
                    LOGGER.warn("PaperStream: the proxy tried /{}, not allowed by proxy.exec-allowed", command);
                    final JsonObject denied = new JsonObject();
                    denied.addProperty("op", "result");
                    denied.addProperty("id", id);
                    denied.addProperty("denied", true);
                    final JsonArray why = new JsonArray();
                    why.add("This server does not allow /" + firstWord(command) + " from the proxy (proxy.exec-allowed in paperstream.yml).");
                    denied.add("lines", why);
                    try {
                        send(denied);
                    } catch (final IOException ex) {
                        LOGGER.warn("PaperStream: could not answer the proxy");
                    }
                    return;
                }
                LOGGER.info("PaperStream: the proxy runs /{}", command);
                server.execute(() -> {
                    final List<String> lines = new ArrayList<>();
                    final CommandSender sender = Bukkit.createCommandSender(component ->
                        lines.add(PlainTextComponentSerializer.plainText().serialize(component)));
                    try {
                        Bukkit.dispatchCommand(sender, command);
                    } catch (final RuntimeException ex) {
                        lines.add("Error: " + ex.getMessage());
                    }
                    final JsonObject result = new JsonObject();
                    result.addProperty("op", "result");
                    result.addProperty("id", id);
                    final JsonArray array = new JsonArray();
                    lines.forEach(array::add);
                    result.add("lines", array);
                    try {
                        send(result);
                    } catch (final IOException ex) {
                        LOGGER.warn("PaperStream: could not send the command output to the proxy");
                    }
                });
            }
            case "restart" -> {
                if (!PaperStreamConfig.proxyAllowRestart) {
                    LOGGER.warn("PaperStream: the proxy asked for a restart, proxy.allow-restart is off");
                    return;
                }
                LOGGER.info("PaperStream: the proxy restarts this server");
                server.execute(org.spigotmc.RestartCommand::restart);
            }
            case "secret" -> {
                final Stored current = stored;
                final String secret = message.get("value").getAsString();
                if (current != null && !secret.isEmpty() && !secret.equals(current.secret())) {
                    final Stored updated = new Stored(current.host(), current.port(), current.server(),
                        current.key(), secret);
                    try {
                        save(updated);
                        stored = updated;
                        server.execute(() -> applyForwarding(server, secret));
                        LOGGER.info("PaperStream: the proxy has a new forwarding secret, updated");
                    } catch (final IOException ex) {
                        LOGGER.error("PaperStream: could not save the new forwarding secret", ex);
                    }
                }
            }
            case "pong" -> proxyAnswersPings = true;
            case "moved" -> {
                final CountDownLatch latch = moved;
                if (latch != null) {
                    latch.countDown();
                }
            }
            case "unlinked" -> {
                LOGGER.warn("PaperStream: the proxy removed this server");
                server.execute(() -> reset(server));
            }
            default -> {
                // pong and future messages
            }
        }
    }

    private static String firstWord(final String command) {
        String word = command.trim();
        if (word.startsWith("/")) {
            word = word.substring(1);
        }
        final int space = word.indexOf(' ');
        word = (space < 0 ? word : word.substring(0, space)).toLowerCase(java.util.Locale.ROOT);
        // "minecraft:say" counts as "say"
        final int colon = word.indexOf(':');
        return colon < 0 ? word : word.substring(colon + 1);
    }

    /** Whether proxy.exec-allowed lets the proxy run this command. */
    static boolean execAllowed(final String command) {
        final java.util.List<String> allowed = PaperStreamConfig.proxyExecAllowed;
        if (allowed.contains("*")) {
            return true;
        }
        final String word = firstWord(command);
        // execute could run any other command, so only "*" allows it
        return !word.isEmpty() && !word.equals("execute") && allowed.contains(word);
    }

    /**
     * Called from MinecraftServer#stopServer before players are kicked: the proxy moves them
     * to other servers first, or keeps them in its limbo until this server is back.
     */
    public static void beforeShutdown(final MinecraftServer server) {
        if (!PaperStreamConfig.proxyAnnounceShutdown || stopAnnounced || connection == null || server.getPlayerCount() == 0) {
            return;
        }
        stopAnnounced = true;
        final CountDownLatch latch = new CountDownLatch(1);
        moved = latch;
        try {
            final JsonObject shutdown = new JsonObject();
            shutdown.addProperty("op", "shutdown");
            send(shutdown);
            LOGGER.info("PaperStream: asked the proxy to move {} player(s) before stopping", server.getPlayerCount());
            if (!latch.await(PaperStreamConfig.proxyLinkShutdownWait, TimeUnit.SECONDS)) {
                LOGGER.info("PaperStream: the proxy did not answer in time, stopping anyway");
            }
        } catch (final IOException ex) {
            LOGGER.warn("PaperStream: could not tell the proxy about the stop: {}", ex.getMessage());
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
