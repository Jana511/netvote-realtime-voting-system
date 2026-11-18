package network;

import backend.EventBus;
import backend.PollService;
import backend.SessionManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The TCP server for desktop clients.
 *
 *   ServerSocket listens on a port (default 5000).
 *   accept() waits for the next client and returns a Socket for it.
 *   Each client is served by its own ClientHandler on a thread pool,
 *   so a slow or crashed client never blocks the others.
 */
public class TcpServer implements Runnable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int port;
    private final PollService polls;
    private final SessionManager sessions;
    private final EventBus bus;
    private final ExecutorService pool = Executors.newCachedThreadPool();   // one thread per connected client
    private final AtomicInteger connected = new AtomicInteger();
    private ServerSocket serverSocket;

    public TcpServer(int port, PollService polls, SessionManager sessions, EventBus bus) {
        this.port = port;
        this.polls = polls;
        this.sessions = sessions;
        this.bus = bus;
    }

    /** Opens the port (throws if it is already in use) and starts accepting clients in the background. */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        Thread t = new Thread(this, "tcp-accept");
        t.start();
    }

    @Override
    public void run() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();          // blocks until a client connects
                socket.setTcpNoDelay(true);                     // send small messages immediately
                socket.setKeepAlive(true);
                int n = connected.incrementAndGet();
                log("Desktop client connected from " + socket.getRemoteSocketAddress() + "  (" + n + " connected)");
                pool.execute(new ClientHandler(socket, polls, sessions, bus, this));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) log("Accept failed: " + e.getMessage());
            }
        }
    }

    void clientGone(String who) {
        log(who + " disconnected  (" + connected.decrementAndGet() + " desktop client(s) connected)");
    }

    static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] [TCP] " + msg);
    }
}
