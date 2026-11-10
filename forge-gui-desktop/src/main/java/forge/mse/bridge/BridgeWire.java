package forge.mse.bridge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The line the bridge talks on: one JSON object a line, UTF-8, to one table connected on
 * 127.0.0.1 (never another address – nothing here goes online) and, if wanted, to a file.
 *
 * {@link #send} never waits for the socket: it numbers the message ("n"), puts the line in a
 * queue and returns, so Forge's game thread is never held up by a slow or missing reader. One
 * thread writes the queue out, another reads the table's lines and hands them to the listener.
 */
public final class BridgeWire {
    private static final String END = "\u0000end";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final ServerSocket server;
    private final Writer file;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final CountDownLatch written = new CountDownLatch(1);
    private final Object sendLock = new Object();
    private long count = 0;
    private volatile Writer client;
    private volatile Socket socket;
    private volatile boolean closing;
    private volatile Consumer<JsonObject> listener = m -> { };
    private volatile Runnable onConnect = () -> { };
    private volatile Runnable onDisconnect = () -> { };

    /**
     * @param port  the port to listen on (0: any free one – see {@link #getPort}), or -1 for no socket
     * @param out   a file every line is also written to, or null
     */
    public BridgeWire(final int port, final Path out) throws IOException {
        server = port < 0 ? null : new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
        file = out == null ? null : Files.newBufferedWriter(out, StandardCharsets.UTF_8);
    }

    public int getPort() {
        return server == null ? -1 : server.getLocalPort();
    }

    /** What the table sends: called on the reader thread, one message at a time. */
    public void setListener(final Consumer<JsonObject> listener0) {
        listener = listener0;
    }

    /** A table connected (called before any line is written to it). */
    public void setOnConnect(final Runnable r) {
        onConnect = r;
    }

    /** The table went away. */
    public void setOnDisconnect(final Runnable r) {
        onDisconnect = r;
    }

    /** Starts the threads. Lines sent before a table connects are kept for the file only. */
    public void start() {
        if (server != null) {
            daemon("mse-bridge-accept", this::accept).start();
        }
        daemon("mse-bridge-write", this::write).start();
    }

    public boolean awaitClient(final long seconds) throws InterruptedException {
        return connected.await(seconds, TimeUnit.SECONDS);
    }

    public boolean hasClient() {
        return client != null;
    }

    /** Queues a message (adds its number "n"). Safe from any thread; never blocks on the socket. */
    public void send(final JsonObject message) {
        if (closing) {
            return;
        }
        synchronized (sendLock) {
            message.addProperty("n", count++);
            queue.add(GSON.toJson(message));
        }
    }

    public static String toJson(final JsonElement e) {
        return GSON.toJson(e);
    }

    /** Writes out what's queued (waiting a few seconds at most), then closes the socket and the file. */
    public void close() {
        synchronized (sendLock) {
            if (closing) {
                return;
            }
            closing = true;
            queue.add(END);
        }
        try {
            written.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(socket);
        if (server != null) {
            try {
                server.close();
            } catch (final IOException e) {
                // closing anyway
            }
        }
        if (file != null) {
            try {
                file.close();
            } catch (final IOException e) {
                // closing anyway
            }
        }
    }

    // ------------------------------------------------------------------ threads

    private static Thread daemon(final String name, final Runnable r) {
        final Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** One table at a time; a new one may connect after the last one left. */
    private void accept() {
        while (!closing) {
            try {
                final Socket s = server.accept();
                if (client != null) {         // someone is already watching
                    closeQuietly(s);
                    continue;
                }
                s.setTcpNoDelay(true);
                final Writer w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                socket = s;
                onConnect.run();
                client = w;
                connected.countDown();
                daemon("mse-bridge-read", () -> read(s)).start();
            } catch (final IOException e) {
                if (!closing) {
                    System.err.println("MSE bridge: " + e);
                }
                return;
            }
        }
    }

    private void write() {
        try {
            while (true) {
                final String line = queue.take();
                if (line == END) {
                    break;
                }
                if (file != null) {
                    try {
                        file.write(line);
                        file.write('\n');
                        if (queue.isEmpty()) {
                            file.flush();
                        }
                    } catch (final IOException e) {
                        System.err.println("MSE bridge: can't write the file: " + e);
                    }
                }
                final Writer w = client;
                if (w != null) {
                    try {
                        w.write(line);
                        w.write('\n');
                        if (queue.isEmpty()) {
                            w.flush();
                        }
                    } catch (final IOException e) {
                        lost(w);
                    }
                }
            }
            final Writer w = client;
            if (w != null) {
                w.flush();
            }
            if (file != null) {
                file.flush();
            }
        } catch (final InterruptedException | IOException e) {
            // the end either way
        } finally {
            written.countDown();
        }
    }

    private void read(final Socket s) {
        final Writer mine = client;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject message = null;
                try {
                    final JsonElement e = JsonParser.parseString(line);
                    if (e.isJsonObject()) {
                        message = e.getAsJsonObject();
                    }
                } catch (final RuntimeException e) {
                    // not JSON: answered below
                }
                if (message == null || !message.has("t")) {
                    final JsonObject err = new JsonObject();
                    err.addProperty("t", "error");
                    err.addProperty("text", "Not a message (one JSON object with \"t\" a line): " + shorten(line));
                    send(err);
                    continue;
                }
                try {
                    listener.accept(message);
                } catch (final RuntimeException e) {
                    final JsonObject err = new JsonObject();
                    err.addProperty("t", "error");
                    err.addProperty("text", "Couldn't do " + shorten(line) + ": " + e);
                    send(err);
                }
            }
        } catch (final IOException e) {
            // the table went away
        }
        lost(mine);
    }

    private synchronized void lost(final Writer w) {
        if (w == null || client != w) {
            return;
        }
        client = null;
        closeQuietly(socket);
        if (!closing) {
            onDisconnect.run();
        }
    }

    private static void closeQuietly(final Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (final IOException e) {
                // closing anyway
            }
        }
    }

    private static String shorten(final String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
