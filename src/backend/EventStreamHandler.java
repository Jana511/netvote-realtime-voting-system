package backend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * GET /api/events?token=...   Live updates for web pages (Server-Sent Events).
 *
 * Normally an HTTP response ends after it is sent. Here the server keeps the
 * response OPEN and writes a new "event" into it every time something changes:
 *
 *     event: state
 *     data: {"poll":{...},"users":[...],"me":{...}}
 *     (empty line)
 *
 * The browser's EventSource object receives each event immediately, so votes
 * appear on every screen at once - no more asking the server every 2 seconds.
 * A comment line ": ping" every 5 s detects pages that were closed.
 * The token is optional: without it the page gets live updates but is not
 * counted as an online user (e.g. the Results page before logging in).
 */
public class EventStreamHandler implements HttpHandler {
    private static final int PING_SECONDS = 5;

    private final PollService polls;
    private final SessionManager sessions;
    private final EventBus bus;

    EventStreamHandler(PollService polls, SessionManager sessions, EventBus bus) {
        this.polls = polls;
        this.sessions = sessions;
        this.bus = bus;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String token = queryParam(ex.getRequestURI().getRawQuery(), "token");
        SessionManager.Session me = sessions.find(token);

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);                      // 0 = length unknown: the response stays open

        BlockingQueue<Boolean> wake = new LinkedBlockingQueue<>();
        EventBus.Listener listener = () -> wake.offer(Boolean.TRUE);
        bus.subscribe(listener);
        if (me != null) sessions.connected(me);
        OutputStream out = ex.getResponseBody();
        try {
            write(out, "retry: 2000\n\n");                   // if the connection drops, the browser reconnects after 2 s
            sendState(out, token);
            while (true) {
                Boolean signal = wake.poll(PING_SECONDS, TimeUnit.SECONDS);
                if (signal == null) { write(out, ": ping\n\n"); continue; }   // fails if the page is gone
                wake.clear();                                // several changes -> one fresh update
                sendState(out, token);
            }
        } catch (IOException | InterruptedException e) {
            // page closed, browser navigated away, or network lost
        } finally {
            bus.unsubscribe(listener);
            if (me != null) sessions.disconnected(me);
            ex.close();
        }
    }

    private void sendState(OutputStream out, String token) throws IOException {
        SessionManager.Session me = sessions.lookup(token);  // null after logout / server restart
        write(out, "event: state\ndata: " + ApiHandler.stateJson(polls, sessions, me) + "\n\n");
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();                                         // push it to the browser now
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name))
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }
}
