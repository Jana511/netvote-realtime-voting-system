package network;

import static common.MessageProtocol.*;

import backend.ApiException;
import backend.EventBus;
import backend.PollService;
import backend.SessionManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Talks to ONE desktop client over its TCP socket.
 *
 *  - Reading: a loop reads one line at a time (readLine) and handles the command.
 *  - Writing: when anything changes, EventBus calls changed(), and this handler
 *    pushes only the messages this client has not seen yet.
 *  - If the client crashes, closes, or stays silent for 90 s, the connection is
 *    closed, the user is logged out, and everybody else is told - the server
 *    and the other clients keep running.
 */
public class ClientHandler implements Runnable, EventBus.Listener {
    private static final int READ_TIMEOUT_MS = 90_000;   // the client sends PING every 20 s

    private final Socket socket;
    private final PollService polls;
    private final SessionManager sessions;
    private final EventBus bus;
    private final TcpServer server;
    private PrintWriter out;
    private volatile SessionManager.Session session;    // null until LOGIN succeeds

    // what this client has already been sent, so only changes are pushed
    private String sentPollKey, sentResults, sentUsers;
    private int sentVoted = -2;

    ClientHandler(Socket socket, PollService polls, SessionManager sessions, EventBus bus, TcpServer server) {
        this.socket = socket;
        this.polls = polls;
        this.sessions = sessions;
        this.bus = bus;
        this.server = server;
    }

    @Override
    public void run() {
        String reason = "closed the connection";
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            send(encode(WELCOME, "NetVote server ready. Please log in."));
            String line;
            while ((line = in.readLine()) != null) {          // null = the client closed the connection
                if (line.isBlank()) continue;
                if (!handle(decode(line))) { reason = "quit"; break; }
            }
        } catch (SocketTimeoutException e) {
            reason = "timed out (no message for " + READ_TIMEOUT_MS / 1000 + " s)";
        } catch (IOException e) {
            reason = "connection lost (" + e.getMessage() + ")";
        } finally {
            bus.unsubscribe(this);
            SessionManager.Session s = session;
            if (s != null) {
                sessions.disconnected(s);
                sessions.logout(s);                          // tells everyone the user left
            }
            try { socket.close(); } catch (IOException ignored) {}
            server.clientGone((s != null ? s.username : String.valueOf(socket.getRemoteSocketAddress())) + " " + reason + ";");
        }
    }

    /** Handles one command. Returns false when the client wants to disconnect. */
    private boolean handle(String[] m) {
        String cmd = m[0];
        try {
            switch (cmd) {
                case HELLO: send(encode(WELCOME, "NetVote server ready. Please log in.")); return true;
                case PING:  send(PONG); return true;
                case QUIT:  return false;
                case LOGIN: login(m); return true;
            }
            SessionManager.Session me = session;
            if (me == null) throw ApiException.unauthorized("Please log in first.");

            switch (cmd) {
                case GET_POLL:
                    forgetSentState();
                    sendUpdates();
                    break;
                case SUBMIT_VOTE: {
                    int index;
                    try { index = Integer.parseInt(arg(m, 1).trim()); }
                    catch (NumberFormatException e) { throw ApiException.badRequest("Invalid option."); }
                    polls.vote(me.username, index);           // the server checks and counts the vote
                    TcpServer.log("Vote from " + me.username + " (desktop) for option " + index);
                    synchronized (this) { sentVoted = index; }
                    send(encode(VOTE_ACCEPTED, index));
                    break;
                }
                case CREATE_POLL: {
                    requireAdmin(me);
                    List<String> options = m.length > 2 ? Arrays.asList(m).subList(2, m.length) : new ArrayList<>();
                    int id = polls.createPoll(me.username, arg(m, 1), options).id;
                    TcpServer.log("Poll #" + id + " started by " + me.username + " (desktop)");
                    break;
                }
                case CLOSE_POLL:
                    requireAdmin(me);
                    polls.closePoll();
                    TcpServer.log("Poll closed by " + me.username + " (desktop)");
                    break;
                default:
                    throw ApiException.badRequest("Unknown command: " + cmd);
            }
        } catch (ApiException e) {
            send(encode(ERROR, e.getMessage()));
        }
        return true;
    }

    private void login(String[] m) {
        if (session != null) throw ApiException.conflict("Already logged in.");
        SessionManager.Session s = sessions.login(arg(m, 1), arg(m, 2), true);   // same rules as the website
        sessions.connected(s);
        session = s;
        TcpServer.log(s.username + (s.admin ? " (admin)" : "") + " logged in from the desktop client");
        send(encode(LOGIN_OK, s.username, s.admin ? "1" : "0"));
        forgetSentState();
        bus.subscribe(this);       // from now on, receive live updates
        sendUpdates();
    }

    private static void requireAdmin(SessionManager.Session me) {
        if (!me.admin) throw ApiException.forbidden("Only the administrator can do this.");
    }

    private static String arg(String[] m, int i) { return i < m.length ? m[i] : ""; }

    // ---------------------------------------------------------------- pushing updates
    /** Called by EventBus whenever the poll or the user list changed. */
    @Override
    public void changed() {
        sendUpdates();
    }

    private synchronized void forgetSentState() {
        sentPollKey = sentResults = sentUsers = null;
        sentVoted = -2;
    }

    /** Sends only what is different from what this client already has. */
    private synchronized void sendUpdates() {
        SessionManager.Session me = session;
        if (me == null) return;
        PollService.Snapshot p = polls.snapshot();

        String pollKey = p == null ? "none" : p.id + "|" + p.active + "|" + p.options;
        if (!pollKey.equals(sentPollKey)) {
            if (p != null && !p.active && (p.id + "|true|" + p.options).equals(sentPollKey))
                send(encode(POLL_CLOSED, p.id));               // it was open before -> just closed
            if (p == null) send(encode(NO_POLL));
            else {
                List<Object> a = new ArrayList<>(Arrays.asList(p.id, p.question, p.active ? "1" : "0"));
                a.addAll(p.options);
                send(encode(POLL, a.toArray()));
            }
            sentPollKey = pollKey;
            sentResults = null;
            sentVoted = -2;
        }
        if (p != null) {
            int voted = polls.votedIndex(me.username);
            if (voted >= 0 && voted != sentVoted) send(encode(VOTED, voted));
            sentVoted = voted;

            List<Object> a = new ArrayList<>(Arrays.asList(p.id, p.total));
            for (int c : p.counts) a.add(c);
            String results = encode(RESULT_UPDATE, a.toArray());
            if (!results.equals(sentResults)) { send(results); sentResults = results; }
        }
        String users = encode(USERS, sessions.onlineUsers().toArray());
        if (!users.equals(sentUsers)) { send(users); sentUsers = users; }
    }

    private void send(String line) {
        PrintWriter o = out;
        if (o != null) synchronized (o) { o.println(line); }   // println adds '\n'; autoflush sends it now
    }
}
