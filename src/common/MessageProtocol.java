package common;

import java.util.ArrayList;
import java.util.List;

/**
 * NetVote TCP message protocol - shared by the server and the desktop client.
 *
 * TCP delivers a continuous stream of bytes, not separate messages, so we
 * define our own framing:
 *   - one message = one line of text, ending with '\n'
 *   - fields are separated by '|':      COMMAND|arg1|arg2|...
 *   - '|', '\' and newline inside a field are escaped (\p, \\, \n), so a poll
 *     question such as "A|B?" can never break a message apart.
 *
 * Client -> server
 *   HELLO                                  connection test           -> WELCOME
 *   LOGIN|username|adminPassword           password empty for voters -> LOGIN_OK or ERROR
 *   GET_POLL                               ask for everything again
 *   SUBMIT_VOTE|optionIndex                                          -> VOTE_ACCEPTED or ERROR
 *   CREATE_POLL|question|opt1|opt2|...     admin only
 *   CLOSE_POLL                             admin only
 *   PING                                   "I am still here" (every 20 s) -> PONG
 *   QUIT                                   disconnect politely
 *
 * Server -> client (pushed whenever something changes)
 *   WELCOME|text
 *   LOGIN_OK|username|1 or 0 (admin)
 *   POLL|id|question|1 or 0 (open)|opt1|opt2|...
 *   NO_POLL
 *   RESULT_UPDATE|pollId|totalVotes|count1|count2|...
 *   VOTED|optionIndex                      you voted for this option earlier
 *   VOTE_ACCEPTED|optionIndex
 *   POLL_CLOSED|pollId
 *   USERS|name1|name2|...                  everyone online (desktop + web)
 *   ERROR|message
 *   PONG
 */
public final class MessageProtocol {
    public static final int DEFAULT_PORT = 5000;

    // client -> server
    public static final String HELLO = "HELLO";
    public static final String LOGIN = "LOGIN";
    public static final String GET_POLL = "GET_POLL";
    public static final String SUBMIT_VOTE = "SUBMIT_VOTE";
    public static final String CREATE_POLL = "CREATE_POLL";
    public static final String CLOSE_POLL = "CLOSE_POLL";
    public static final String PING = "PING";
    public static final String QUIT = "QUIT";
    // server -> client
    public static final String WELCOME = "WELCOME";
    public static final String LOGIN_OK = "LOGIN_OK";
    public static final String POLL = "POLL";
    public static final String NO_POLL = "NO_POLL";
    public static final String RESULT_UPDATE = "RESULT_UPDATE";
    public static final String VOTED = "VOTED";
    public static final String VOTE_ACCEPTED = "VOTE_ACCEPTED";
    public static final String POLL_CLOSED = "POLL_CLOSED";
    public static final String USERS = "USERS";
    public static final String ERROR = "ERROR";
    public static final String PONG = "PONG";

    private MessageProtocol() {}

    /** encode("SUBMIT_VOTE", 2)  ->  "SUBMIT_VOTE|2" */
    public static String encode(String command, Object... args) {
        StringBuilder sb = new StringBuilder(escape(command));
        for (Object a : args) sb.append('|').append(escape(String.valueOf(a)));
        return sb.toString();
    }

    /** decode("SUBMIT_VOTE|2")  ->  ["SUBMIT_VOTE", "2"] */
    public static String[] decode(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char n = line.charAt(++i);
                cur.append(n == 'p' ? '|' : n == 'n' ? '\n' : n);
            } else if (c == '|') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts.toArray(new String[0]);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '|':  sb.append("\\p"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
