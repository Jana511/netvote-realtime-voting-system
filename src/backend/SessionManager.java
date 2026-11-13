package backend;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is logged in. Logging in gives the browser a random token; the browser
 * sends it with every request (header "Authorization: Bearer <token>").
 *
 * A user counts as ONLINE while their page keeps contacting the server
 * (the pages refresh every 2 seconds). If a tab is closed, the user drops out
 * of the online list after ONLINE_MS.
 */
public class SessionManager {
    static final long ONLINE_MS = 10_000;               // seen in the last 10 s -> online
    static final long EXPIRE_MS = 2 * 60 * 60 * 1000;   // forget a session after 2 h without activity

    static final class Session {
        final String token, username;
        final boolean admin;
        volatile long lastSeen = System.currentTimeMillis();

        Session(String token, String username, boolean admin) {
            this.token = token;
            this.username = username;
            this.admin = admin;
        }

        boolean online(long now) { return now - lastSeen < ONLINE_MS; }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();   // token -> session
    private final String adminPassword;
    private final SecureRandom random = new SecureRandom();

    SessionManager(String adminPassword) { this.adminPassword = adminPassword; }

    synchronized Session login(String username, String password) {
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
        Session s = new Session(newToken(), username, admin);
        sessions.put(s.token, s);
        return s;
    }

    /** Finds the session for a token and marks it as active. Returns null if the token is unknown. */
    Session find(String token) {
        if (token == null || token.isEmpty()) return null;
        Session s = sessions.get(token);
        if (s != null) s.lastSeen = System.currentTimeMillis();
        return s;
    }

    void logout(Session s) { sessions.remove(s.token); }

    List<String> onlineUsers() {
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        for (Session s : sessions.values()) if (s.online(now)) names.add(s.username);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Called regularly to forget very old sessions. */
    void removeExpired() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> now - s.lastSeen > EXPIRE_MS);
    }

    private String newToken() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
