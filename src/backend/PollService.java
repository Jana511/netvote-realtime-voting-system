package backend;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * All the voting rules live here. Only the server changes poll data; the
 * website just asks. Every public method is synchronized because the web
 * server handles many requests at the same time on different threads, and
 * two votes must never be counted at the same moment.
 *
 * The newest poll is the "current" poll (open or closed). Closed polls form the history.
 */
public class PollService {
    public static final int MIN_OPTIONS = 2, MAX_OPTIONS = 10, MAX_QUESTION = 200, MAX_OPTION = 80;

    private final Storage storage;
    private final List<Poll> polls;      // oldest first
    private final Random random = new Random();

    PollService(Storage storage) {
        this.storage = storage;
        this.polls = storage.load();
    }

    synchronized int pollCount() { return polls.size(); }

    synchronized Poll current() {
        return polls.isEmpty() ? null : polls.get(polls.size() - 1);
    }

    /** Closed polls, newest first. */
    synchronized List<Poll> history() {
        List<Poll> h = new ArrayList<>();
        for (Poll p : polls) if (!p.active) h.add(p);
        Collections.reverse(h);
        return h;
    }

    /** JSON of the current poll, built while holding the lock so no vote can change it half-way. */
    synchronized String currentJson(boolean includeVoters) {
        return Json.poll(current(), includeVoters);
    }

    synchronized String historyJson() {
        StringBuilder sb = new StringBuilder("[");
        List<Poll> h = history();
        for (int i = 0; i < h.size(); i++) sb.append(i > 0 ? "," : "").append(Json.poll(h.get(i), false));
        return sb.append(']').toString();
    }

    synchronized int votedIndex(String username) {
        Poll p = current();
        if (p == null || username == null) return -1;
        return p.votes.getOrDefault(username, -1);
    }

    // ---------------------------------------------------------------- admin actions
    synchronized Poll createPoll(String admin, String question, List<String> rawOptions) {
        Poll cur = current();
        if (cur != null && cur.active) throw ApiException.conflict("Close the current poll before starting a new one.");
        question = question == null ? "" : question.trim();
        if (question.isEmpty()) throw ApiException.badRequest("The question cannot be empty.");
        if (question.length() > MAX_QUESTION) throw ApiException.badRequest("The question is too long (max " + MAX_QUESTION + " characters).");

        List<String> options = new ArrayList<>();
        for (String o : rawOptions) {
            o = o == null ? "" : o.trim();
            if (o.isEmpty()) continue;
            if (o.length() > MAX_OPTION) throw ApiException.badRequest("An option is too long (max " + MAX_OPTION + " characters).");
            boolean duplicate = false;
            for (String existing : options) if (existing.equalsIgnoreCase(o)) duplicate = true;
            if (!duplicate) options.add(o);
        }
        if (options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS)
            throw ApiException.badRequest("A poll needs " + MIN_OPTIONS + " to " + MAX_OPTIONS + " different options.");

        int id = cur == null ? 1 : cur.id + 1;
        Poll p = new Poll(id, question, options, admin, now(), true, null);
        polls.add(p);
        storage.save(polls);
        return p;
    }

    synchronized Poll closePoll() {
        Poll p = current();
        if (p == null || !p.active) throw ApiException.conflict("There is no active poll to close.");
        p.active = false;
        p.closedAt = now();
        storage.save(polls);
        return p;
    }

    /** Deletes every poll and the history. */
    synchronized void resetAll() {
        polls.clear();
        storage.save(polls);
    }

    /** Testing helper: votes from imaginary users, so charts can be shown with one computer. */
    synchronized int addDemoVotes(int count) {
        Poll p = current();
        if (p == null || !p.active) throw ApiException.conflict("Start a poll first.");
        String[] names = {"kasun", "nimali", "tharindu", "dilini", "sahan", "ishara", "chamodi", "ravindu", "hiruni", "pasindu",
                          "dulanjali", "nuwan", "sachini", "lahiru", "malsha", "yasiru", "thilini", "dinuka", "sewwandi", "akila"};
        int added = 0;
        for (String n : names) {
            if (added >= count) break;
            String name = "demo_" + n;
            if (p.votes.containsKey(name)) continue;
            p.votes.put(name, random.nextInt(p.options.size()));
            added++;
        }
        if (added == 0) throw ApiException.conflict("All demo voters have already voted.");
        storage.save(polls);
        return added;
    }

    // ---------------------------------------------------------------- voter action
    synchronized void vote(String username, int index) {
        Poll p = current();
        if (p == null || !p.active) throw ApiException.conflict("There is no active poll. Voting is closed.");
        if (index < 0 || index >= p.options.size()) throw ApiException.badRequest("Invalid option.");
        if (p.votes.containsKey(username)) throw ApiException.conflict("You have already voted in this poll.");
        p.votes.put(username, index);
        storage.save(polls);
    }

    private static String now() { return LocalDateTime.now().withNano(0).toString(); }
}
