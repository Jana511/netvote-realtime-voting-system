package client;

import common.MessageProtocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The network part of the desktop client.
 *   connect()        opens a TCP Socket to the server (5 s timeout)
 *   startListening() starts a background thread that reads server messages forever,
 *                    so the window never freezes while waiting for the network
 *   send()           sends one protocol line
 * A second background thread sends PING every 20 s so the server knows we are alive.
 */
public class VoteClient {
    public interface Listener {
        void onMessage(String[] message);          // called on the reader thread
        void onDisconnected(String reason);
    }

    private static final int CONNECT_TIMEOUT_MS = 5000, PING_EVERY_MS = 20_000;

    private final Listener listener;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean closing;

    public VoteClient(Listener listener) { this.listener = listener; }

    public void connect(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    public void startListening() {
        Thread reader = new Thread(() -> {
            String reason = "The server closed the connection.";
            try {
                String line;
                while ((line = in.readLine()) != null) listener.onMessage(MessageProtocol.decode(line));
            } catch (IOException e) {
                reason = "Connection lost: " + e.getMessage();
            }
            if (!closing) listener.onDisconnected(reason);
        }, "server-listener");
        reader.setDaemon(true);
        reader.start();

        Thread heartbeat = new Thread(() -> {
            while (!closing && !socket.isClosed()) {
                try { Thread.sleep(PING_EVERY_MS); } catch (InterruptedException e) { return; }
                send(MessageProtocol.PING);
            }
        }, "heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    public synchronized void send(String command, Object... args) {
        if (out != null) out.println(MessageProtocol.encode(command, args));
    }

    public void close() {
        closing = true;
        try {
            send(MessageProtocol.QUIT);
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
    }
}
