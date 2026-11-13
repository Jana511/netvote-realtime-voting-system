package backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One poll: question, options and the votes received (username -> option index). */
public class Poll {
    final int id;
    final String question;
    final List<String> options;
    final String createdBy;
    final String createdAt;          // ISO date-time, e.g. 2026-11-12T13:16:05
    boolean active;
    String closedAt;                 // null while the poll is open
    final Map<String, Integer> votes = new LinkedHashMap<>();

    Poll(int id, String question, List<String> options, String createdBy, String createdAt, boolean active, String closedAt) {
        this.id = id;
        this.question = question;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.active = active;
        this.closedAt = closedAt;
    }

    int[] counts() {
        int[] c = new int[options.size()];
        for (int index : votes.values()) c[index]++;
        return c;
    }

    int total() { return votes.size(); }
}
