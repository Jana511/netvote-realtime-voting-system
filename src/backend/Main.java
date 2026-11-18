package backend;

import com.sun.net.httpserver.HttpServer;
import network.TcpServer;

import java.io.IOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NetVote server – starts BOTH doors into the same poll:
 *   - HTTP  (default port 8080): the website + REST API + live updates for browsers
 *   - TCP   (default port 5000): the Java desktop client (ClientGUI)
 *
 *   java -cp out backend.Main [httpPort] [adminPassword] [tcpPort]     defaults: 8080 admin123 5000
 *
 * Run it from the NetVote folder: the website is read from ./web and the
 * data is saved in ./data.
 */
public class Main {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws IOException {
        int httpPort, tcpPort;
        try {
            httpPort = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
            tcpPort = args.length > 2 ? Integer.parseInt(args[2]) : 5000;
        } catch (NumberFormatException e) {
            System.err.println("Ports must be numbers, e.g.  java -cp out backend.Main 8080 admin123 5000");
            return;
        }
        String adminPassword = args.length > 1 ? args[1] : "admin123";

        Path web = Paths.get("web");
        if (!Files.isRegularFile(web.resolve("index.html"))) {
            System.err.println("Cannot find web/index.html in " + Paths.get("").toAbsolutePath());
            System.err.println("Start the server from the NetVote folder (the folder that contains 'web' and 'src').");
            return;
        }

        EventBus bus = new EventBus();
        Storage storage = new Storage(Paths.get("data"));
        PollService polls = new PollService(storage, bus);
        SessionManager sessions = new SessionManager(adminPassword, bus);

        // ---- HTTP server for browsers
        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress(httpPort), 0);
        } catch (BindException e) {
            System.err.println("Port " + httpPort + " is already in use (is NetVote or another server already running?).");
            System.err.println("Close it, or choose another port:  java -cp out backend.Main 8081");
            return;
        }
        http.createContext("/api/events", new EventStreamHandler(polls, sessions, bus));
        http.createContext("/api/", new ApiHandler(polls, sessions));
        http.createContext("/", new StaticFileHandler(web));
        // a growing pool: every open web page keeps one live-update connection (and thread) busy
        http.setExecutor(Executors.newCachedThreadPool());

        // ---- TCP server for desktop clients
        TcpServer tcp = new TcpServer(tcpPort, polls, sessions, bus);
        try {
            tcp.start();
        } catch (BindException e) {
            System.err.println("TCP port " + tcpPort + " is already in use. Choose another one, e.g.:  java -cp out backend.Main "
                    + httpPort + " " + adminPassword + " 5001");
            return;
        }
        http.start();

        // ---- housekeeping
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleAtFixedRate(sessions::removeExpired, 10, 10, TimeUnit.MINUTES);
        // a browser that was closed drops out of the online list after a short grace time:
        // check every 2 s and tell everyone when the list changes
        List<List<String>> lastUsers = new ArrayList<>(Collections.singletonList(sessions.onlineUsers()));
        timer.scheduleAtFixedRate(() -> {
            List<String> now = sessions.onlineUsers();
            if (!now.equals(lastUsers.get(0))) { lastUsers.set(0, now); bus.publish(); }
        }, 2, 2, TimeUnit.SECONDS);

        log("NetVote server started");
        log("Website (this computer):  http://localhost:" + httpPort);
        for (String ip : lanAddresses())
            log("Other devices on the same Wi-Fi:  website http://" + ip + ":" + httpPort
                    + "   desktop client " + ip + " port " + tcpPort);
        log("Desktop client TCP port: " + tcpPort);
        log("Admin password: " + adminPassword);
        log("Data file: " + storage.file().toAbsolutePath() + "  (" + polls.pollCount() + " poll(s) loaded)");
        log("Press Ctrl+C to stop the server.");
    }

    /** The private IPv4 addresses of this computer (what other devices type to reach it). */
    static List<String> lanAddresses() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = (ni.getDisplayName() + " " + ni.getName()).toLowerCase();
                if (name.contains("virtualbox") || name.contains("vmware") || name.contains("hyper-v") || name.contains("docker"))
                    continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) ips.add(a.getHostAddress());
            }
        } catch (SocketException ignored) {}
        return ips;
    }

    static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + msg);
    }
}
