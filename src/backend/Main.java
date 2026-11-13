package backend;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NetVote backend – starts the web server.
 *
 *   java -cp out backend.Main [port] [adminPassword]       defaults: 8080  admin123
 *
 * Run it from the NetVote folder: the website is read from ./web and the
 * data is saved in ./data. Then open http://localhost:8080 in a browser.
 */
public class Main {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws IOException {
        int port = 8080;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) { System.err.println("Port must be a number, e.g. 8080"); return; }
        }
        String adminPassword = args.length > 1 ? args[1] : "admin123";

        Path web = Paths.get("web");
        if (!Files.isRegularFile(web.resolve("index.html"))) {
            System.err.println("Cannot find web/index.html in " + Paths.get("").toAbsolutePath());
            System.err.println("Start the server from the NetVote folder (the folder that contains 'web' and 'src').");
            return;
        }

        Storage storage = new Storage(Paths.get("data"));
        PollService polls = new PollService(storage);
        SessionManager sessions = new SessionManager(adminPassword);

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (BindException e) {
            System.err.println("Port " + port + " is already in use. Is NetVote already running? "
                    + "Close it, or start with another port: java -cp out backend.Main 8081");
            return;
        }
        server.createContext("/api/", new ApiHandler(polls, sessions));
        server.createContext("/", new StaticFileHandler(web));
        server.setExecutor(Executors.newFixedThreadPool(16));   // up to 16 requests handled at the same time
        server.start();

        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(sessions::removeExpired, 10, 10, TimeUnit.MINUTES);

        log("NetVote backend started");
        log("Open  http://localhost:" + port + "  in your browser");
        log("Admin password: " + adminPassword);
        log("Data file: " + storage.file().toAbsolutePath() + "  (" + polls.pollCount() + " poll(s) loaded)");
        log("Press Ctrl+C to stop the server.");
    }

    static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + msg);
    }
}
