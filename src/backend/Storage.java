package backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Saves every poll (with its votes) to a plain text file, so nothing is lost
 * when the server stops. No database is needed.
 *
 * File data/polls.txt - one record per line, fields separated by TAB:
 *   POLL  id  question  active(1/0)  createdBy  createdAt  closedAt  option1  option2 ...
 *   VOTE  username  optionIndex          (belongs to the POLL line above it)
 * TAB, newline and backslash inside text are escaped as \t \n \\ .
 */
public class Storage {
    private final Path file;

    Storage(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        file = dataDir.resolve("polls.txt");
    }

    Path file() { return file; }

    /** Writes to a temporary file first, then replaces the old file, so a crash never leaves half a file. */
    void save(List<Poll> polls) {
        List<String> lines = new ArrayList<>();
        for (Poll p : polls) {
            List<String> f = new ArrayList<>(Arrays.asList("POLL", String.valueOf(p.id), p.question,
                    p.active ? "1" : "0", p.createdBy, p.createdAt, p.closedAt == null ? "" : p.closedAt));
            f.addAll(p.options);
            lines.add(join(f));
            p.votes.forEach((user, index) -> lines.add(join(Arrays.asList("VOTE", user, String.valueOf(index)))));
        }
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Could not save data: " + e);
        }
    }

    List<Poll> load() {
        List<Poll> polls = new ArrayList<>();
        if (!Files.exists(file)) return polls;
        try {
            Poll current = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                List<String> f = split(line);
                if (f.get(0).equals("POLL")) {
                    current = new Poll(Integer.parseInt(f.get(1)), f.get(2), f.subList(7, f.size()),
                            f.get(4), f.get(5), f.get(3).equals("1"), f.get(6).isEmpty() ? null : f.get(6));
                    polls.add(current);
                } else if (f.get(0).equals("VOTE") && current != null) {
                    current.votes.put(f.get(1), Integer.parseInt(f.get(2)));
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not read " + file + " (" + e + "). Starting with no polls.");
            polls.clear();
        }
        return polls;
    }

    // ---------------------------------------------------------------- line format
    private static String join(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append('\t');
            for (char c : fields.get(i).toCharArray()) {
                if (c == '\\') sb.append("\\\\");
                else if (c == '\t') sb.append("\\t");
                else if (c == '\n') sb.append("\\n");
                else if (c != '\r') sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char n = line.charAt(++i);
                cur.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else if (c == '\t') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
