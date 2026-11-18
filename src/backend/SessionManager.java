package backend;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Who is logged in - for BOTH kinds of client:
 *   - browsers log in over HTTP and keep a random token
 *   - desktop clients log in over their TCP connection
 * One username can only be used once at a time, whichever way it connects.
 *
 * A user is ONLINE while they have a live connection (an open TCP socket or an
 * open web page receiving live updates), and for ONLINE_MS after the last one
 * closed - so refreshing a web page does not make the user "leave".
 */
public class SessionManager {
    static final long ONLINE_MS = 10_000;               // grace time after the last connection closed
    static final long EXPIRE_MS = 2 * 60 * 60 * 1000;   // forget an unused web login after 2 h

    public static final class Session {
        public final String token, username;
        public final boolean admin;
        final boolean desktop;                           // true = TCP client, false = browser
        volatile long lastSeen = System.currentTimeMillis();
        final AtomicInteger liveConnections = new AtomicInteger();

        Session(String token, String username, boolean admin, boolean desktop) {
            this.token = token;
            this.username = username;
            this.admin = admin;
            this.desktop = desktop;
        }

        boolean online(long now) { return liveConnections.get() > 0 || now - lastSeen < ONLINE_MS; }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();   // token -> session
    private final String adminPassword;
    private final EventBus bus;
    private final SecureRandom random = new SecureRandom();

    SessionManager(String adminPassword, EventBus bus) {
        this.adminPassword = adminPassword;
        this.bus = bus;
    }

    /** Checks the name and password and creates a session. desktop = logged in over TCP. */
    public synchronized Session login(String username, String password, boolean desktop) {
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
        if (!username.matches("[A-Za-z0-9_]{2,20}"))
            throw ApiException.badRequest("Username must be 2–20 letters, digits or underscores.");
        if (username.toLowerCase().startsWith("demo_"))
            throw ApiException.badRequest("Usernames starting with demo_ are reserved for test votes.");
        boolean admin = !password.isEmpty();
        if (admin && !adminPassword.equals(password)) throw ApiException.unauthorized("Wrong administrator password.");

        long now = System.currentTimeMillis();
        Iterator<Session> it = sessions.values().iterator();
        while (it.hasNext()) {
            Session s = it.next();
            if (!s.username.equalsIgnoreCase(username)) continue;
            if (s.online(now)) throw ApiException.conflict("That username is already connected.");
            it.remove();   // old, inactive login with the same name
        }
        Session s = new Session(newToken(), username, admin, desktop);
        sessions.put(s.token, s);
        bus.publish();     // user list changed
        return s;
    }

    /** Finds the session for a token and marks it as active. Returns null if the token is unknown. */
    Session find(String token) {
        Session s = lookup(token);
        if (s != null) s.lastSeen = System.currentTimeMillis();
        return s;
    }

    /** Finds the session without marking it active. */
    Session lookup(String token) {
        if (token == null || token.isEmpty()) return null;
        return sessions.get(token);
    }

    public void logout(Session s) {
        if (sessions.remove(s.token) != null) bus.publish();
    }

    /** A live connection (TCP socket or browser event stream) opened for this user. */
    public void connected(Session s) {
        s.lastSeen = System.currentTimeMillis();
        if (s.liveConnections.incrementAndGet() == 1) bus.publish();
    }

    /** A live connection closed. A browser stays online for ONLINE_MS more (page refresh). */
    public void disconnected(Session s) {
        s.lastSeen = System.currentTimeMillis();
        s.liveConnections.decrementAndGet();
    }

    public List<String> onlineUsers() {
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        for (Session s : sessions.values()) if (s.online(now)) names.add(s.username);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Called regularly to forget very old web logins. */
    void removeExpired() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> s.liveConnections.get() == 0 && now - s.lastSeen > EXPIRE_MS);
    }

    private String newToken() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
