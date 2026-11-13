package backend;

import java.util.List;

/** Tiny helper to build JSON text by hand (so no external library is needed). */
final class Json {
    private Json() {}

    /** A JSON string literal with special characters escaped. */
    static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    // control characters, and < > & so the text is always safe inside a web page
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String strings(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) sb.append(i > 0 ? "," : "").append(str(list.get(i)));
        return sb.append(']').toString();
    }

    static String ints(int[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) sb.append(i > 0 ? "," : "").append(values[i]);
        return sb.append(']').toString();
    }

    static String error(String message) { return "{\"error\":" + str(message) + "}"; }

    /**
     * A poll as JSON. Who voted is only included when includeVoters is true
     * (for the administrator); which option each person chose is never sent.
     */
    static String poll(Poll p, boolean includeVoters) {
        if (p == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(p.id)
          .append(",\"question\":").append(str(p.question))
          .append(",\"options\":").append(strings(p.options))
          .append(",\"counts\":").append(ints(p.counts()))
          .append(",\"total\":").append(p.total())
          .append(",\"active\":").append(p.active)
          .append(",\"createdBy\":").append(str(p.createdBy))
          .append(",\"createdAt\":").append(str(p.createdAt))
          .append(",\"closedAt\":").append(str(p.closedAt))
          .append(",\"voters\":").append(includeVoters ? strings(new java.util.ArrayList<>(p.votes.keySet())) : "[]");
        return sb.append('}').toString();
    }
}
