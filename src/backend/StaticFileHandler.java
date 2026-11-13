package backend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Sends the website files (HTML, CSS, JS) from the web/ folder to the browser. */
public class StaticFileHandler implements HttpHandler {
    private final Path root;

    StaticFileHandler(Path webFolder) {
        this.root = webFolder.toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
                text(ex, 405, "Method not allowed");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/")) path += "index.html";
            Path file = root.resolve(path.substring(1)).normalize();
            // refuse anything outside web/ (e.g. /../src/backend/Main.java)
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                text(ex, 404, "404 – page not found");
                return;
            }
            byte[] body = Files.readAllBytes(file);
            ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            if (ex.getRequestMethod().equals("HEAD")) {
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
        } finally {
            ex.close();
        }
    }

    private static void text(HttpExchange ex, int status, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    private static String contentType(String name) {
        name = name.toLowerCase();
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css"))  return "text/css; charset=utf-8";
        if (name.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg"))  return "image/svg+xml";
        if (name.endsWith(".ico"))  return "image/x-icon";
        return "application/octet-stream";
    }
}
