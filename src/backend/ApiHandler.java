package backend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The REST API used by the website. Requests send form data
 * (application/x-www-form-urlencoded); every answer is JSON.
 *
 *   GET  /api/state         current poll, online users, and "me" (if logged in)
 *   GET  /api/history       closed polls, newest first
 *   POST /api/login         username, password (empty for voters)  -> {token, username, admin}
 *   POST /api/logout
 *   POST /api/vote          option (index, starting at 0)
 *   POST /api/polls         question, options (one per line)      admin only
 *   POST /api/polls/close                                         admin only
 *   POST /api/demo-votes    count                                 admin only (testing)
 *   POST /api/reset                                               admin only (testing)
 *
 * Logged-in requests carry the header  Authorization: Bearer <token>
 * Errors come back as {"error": "message"} with a 4xx status code.
 */
public class ApiHandler implements HttpHandler {
    private final PollService polls;
    private final SessionManager sessions;

    ApiHandler(PollService polls, SessionManager sessions) {
        this.polls = polls;
        this.sessions = sessions;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        SessionManager.Session me = sessions.find(token(ex));
        int status = 200;
        String body;
        try {
            body = route(ex, method, path, me);
        } catch (ApiException e) {
            status = e.status;
            body = Json.error(e.getMessage());
        } catch (RuntimeException e) {
            status = 500;
            body = Json.error("Server error.");
            e.printStackTrace();
        }
        // GET /api/state is called every 2 s by every open page, so it is not logged
        if (!path.equals("/api/state") || status != 200)
            Main.log(method + " " + path + " -> " + status + (me != null ? "  [" + me.username + "]" : "")
                    + (status >= 400 ? "  " + body : ""));
        send(ex, status, body);
    }

    private String route(HttpExchange ex, String method, String path, SessionManager.Session me) throws IOException {
        if (method.equals("GET")) {
            switch (path) {
                case "/api/state":   return state(me);
                case "/api/history": return polls.historyJson();
                default: throw new ApiException(404, "Unknown API address: " + path);
            }
        }
        if (!method.equals("POST")) throw new ApiException(405, "Method not allowed.");
        Map<String, String> form = readForm(ex);

        if (path.equals("/api/login")) {
            SessionManager.Session s = sessions.login(form.get("username"), form.get("password"));
            Main.log(s.username + (s.admin ? " (admin)" : "") + " logged in from " + ex.getRemoteAddress().getAddress().getHostAddress());
            return "{\"token\":" + Json.str(s.token) + ",\"username\":" + Json.str(s.username) + ",\"admin\":" + s.admin + "}";
        }

        // everything below needs a logged-in user
        if (me == null) throw ApiException.unauthorized("Your session has ended. Please log in again.");

        switch (path) {
            case "/api/logout":
                sessions.logout(me);
                Main.log(me.username + " logged out");
                return ok();

            case "/api/vote": {
                int option;
                try { option = Integer.parseInt(form.getOrDefault("option", "").trim()); }
                catch (NumberFormatException e) { throw ApiException.badRequest("Invalid option."); }
                polls.vote(me.username, option);
                Main.log("Vote from " + me.username + " for option " + option);
                return ok();
            }
        }

        // administrator only
        if (!me.admin) throw ApiException.forbidden("Only the administrator can do this.");
        switch (path) {
            case "/api/polls": {
                String[] options = form.getOrDefault("options", "").split("\\R");
                Poll p = polls.createPoll(me.username, form.get("question"), Arrays.asList(options));
                Main.log("Poll #" + p.id + " started by " + me.username + ": " + p.question);
                return "{\"ok\":true,\"id\":" + p.id + "}";
            }
            case "/api/polls/close": {
                Poll p = polls.closePoll();
                Main.log("Poll #" + p.id + " closed by " + me.username + " with " + p.total() + " votes");
                return ok();
            }
            case "/api/demo-votes": {
                int count;
                try { count = Math.max(1, Math.min(20, Integer.parseInt(form.getOrDefault("count", "5")))); }
                catch (NumberFormatException e) { count = 5; }
                int added = polls.addDemoVotes(count);
                return "{\"ok\":true,\"added\":" + added + "}";
            }
            case "/api/reset":
                polls.resetAll();
                Main.log("All poll data deleted by " + me.username);
                return ok();
            default:
                throw new ApiException(404, "Unknown API address: " + path);
        }
    }

    private String state(SessionManager.Session me) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"poll\":").append(polls.currentJson(me != null && me.admin));
        sb.append(",\"users\":").append(Json.strings(sessions.onlineUsers()));
        sb.append(",\"me\":");
        if (me == null) sb.append("null");
        else sb.append("{\"username\":").append(Json.str(me.username))
               .append(",\"admin\":").append(me.admin)
               .append(",\"voted\":").append(polls.votedIndex(me.username)).append('}');
        return sb.append('}').toString();
    }

    // ---------------------------------------------------------------- helpers
    private static String ok() { return "{\"ok\":true}"; }

    private static String token(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h != null && h.startsWith("Bearer ")) return h.substring(7).trim();
        return null;
    }

    /** Reads a form body such as  username=kasun&password=  (max 64 KB). */
    private static Map<String, String> readForm(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) { raw = in.readNBytes(64 * 1024); }
        String s = new String(raw, StandardCharsets.UTF_8);
        if (s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
