package de.uzl.its.cfg.loops;

import de.uzl.its.cfg.loops.LoopFacts.MethodFacts;
import de.uzl.its.cfg.loops.LoopHeads.LoopHead;
import de.uzl.its.cfg.loops.LoopSummaries.BackEdge;
import de.uzl.its.cfg.loops.LoopSummaries.CountingLoop;
import de.uzl.its.cfg.loops.LoopSummaries.Diamond;
import de.uzl.its.cfg.loops.LoopSummaries.MethodPlans;
import de.uzl.its.cfg.loops.LoopSummaries.Op;
import de.uzl.its.cfg.loops.LoopSummaries.Plan;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The loop facts file: JSON, written by the static analysis and read by the executor. Both use this
 * class (it has no dependencies beyond the JDK, so it can live in the executor's agent jar):
 *
 * <pre>
 * {"version": 1, "methods": {"owner.name(desc)": {
 *     "hash": "...",
 *     "heads": [[at, ordinal, [slot, ...], "kinds"], ...],
 *     "plans": {"jumpOrdinal": plan, ...},
 *     "joins": {"position": joinId, ...}}}}
 * </pre>
 *
 * A plan is {@code {"diamond": {"fall": ops, "jump": ops, "join": id, "stack": delta}}},
 * {@code {"loop": {...the fields of CountingLoop...}}} or {@code {"backEdgeOf": jumpOrdinal}}, the
 * ordinal of the head jump of its counting loop. An op is {@code [opcode, operand, increment]}, or
 * {@code [opcode, 0, 0, diamond]} for a diamond nested in a side. Methods without facts are listed
 * with their hash alone, so that the executor knows they were analysed.
 */
public final class LoopFactsJson {

    private LoopFactsJson() {}

    /** Writes the facts of the methods, by {@link LoopFacts#key}. */
    public static void write(Map<String, MethodFacts> methods, Writer out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\": ").append(LoopFacts.VERSION).append(", \"methods\": {");
        boolean first = true;
        for (Map.Entry<String, MethodFacts> e : new TreeMap<>(methods).entrySet()) {
            sb.append(first ? "\n" : ",\n");
            first = false;
            string(sb, e.getKey());
            sb.append(": ");
            method(sb, e.getValue());
        }
        sb.append("\n}}\n");
        out.write(sb.toString());
    }

    /**
     * Reads a facts file. Returns null if it has another {@link LoopFacts#VERSION}.
     *
     * @throws IOException if it cannot be read or is malformed
     */
    public static Map<String, MethodFacts> read(Reader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) {
            sb.append(buf, 0, n);
        }
        Object root;
        try {
            root = new Parser(sb).document();
        } catch (RuntimeException e) {
            throw new IOException("malformed loop facts: " + e.getMessage(), e);
        }
        Map<?, ?> doc = (Map<?, ?>) root;
        if (((Number) doc.get("version")).intValue() != LoopFacts.VERSION) {
            return null;
        }
        Map<String, MethodFacts> methods = new LinkedHashMap<>();
        try {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) doc.get("methods")).entrySet()) {
                methods.put((String) e.getKey(), method((Map<?, ?>) e.getValue()));
            }
        } catch (RuntimeException e) {
            throw new IOException("malformed loop facts: " + e, e);
        }
        return methods;
    }

    // ---- writing

    private static void method(StringBuilder sb, MethodFacts f) {
        sb.append("{\"hash\": ");
        string(sb, f.hash());
        if (!f.heads().isEmpty()) {
            sb.append(", \"heads\": [");
            for (int k = 0; k < f.heads().size(); k++) {
                LoopHead h = f.heads().get(k);
                sb.append(k == 0 ? "" : ", ").append('[').append(h.at()).append(", ").append(h.ordinal()).append(", [");
                for (int s = 0; s < h.slots().length; s++) {
                    sb.append(s == 0 ? "" : ", ").append(h.slots()[s]);
                }
                sb.append("], ");
                string(sb, new String(h.kinds()));
                sb.append(']');
            }
            sb.append(']');
        }
        Map<Integer, Plan> byJump = new TreeMap<>(f.plans().byJump());
        if (!byJump.isEmpty()) {
            sb.append(", \"plans\": {");
            boolean first = true;
            for (Map.Entry<Integer, Plan> e : byJump.entrySet()) {
                sb.append(first ? "" : ", ").append('"').append(e.getKey()).append("\": ");
                first = false;
                plan(sb, e.getValue(), byJump);
            }
            sb.append('}');
        }
        if (!f.plans().joins().isEmpty()) {
            sb.append(", \"joins\": ");
            intMap(sb, f.plans().joins());
        }
        sb.append('}');
    }

    private static void plan(StringBuilder sb, Plan plan, Map<Integer, Plan> byJump) {
        if (plan instanceof Diamond d) {
            sb.append("{\"diamond\": ");
            diamond(sb, d);
            sb.append('}');
        } else if (plan instanceof CountingLoop l) {
            sb.append("{\"loop\": {\"i\": ").append(l.i()).append(", \"step\": ").append(l.step())
                    .append(", \"bound\": ").append(l.bound()).append(", \"boundConstant\": ").append(l.boundConstant())
                    .append(", \"exitOnJump\": ").append(l.exitOnJump()).append(", \"steps\": ");
            intMap(sb, l.steps());
            sb.append(", \"constants\": ");
            intMap(sb, l.constants());
            sb.append(", \"pure\": ").append(l.pure()).append(", \"inclusive\": ").append(l.inclusive()).append("}}");
        } else if (plan instanceof BackEdge b) {
            Integer head = null;
            for (Map.Entry<Integer, Plan> e : byJump.entrySet()) {
                if (e.getValue() == b.loop()) {
                    head = e.getKey();
                }
            }
            if (head == null) {
                throw new IllegalStateException("back edge without its loop");
            }
            sb.append("{\"backEdgeOf\": ").append(head).append('}');
        }
    }

    private static void diamond(StringBuilder sb, Diamond d) {
        sb.append("{\"fall\": ");
        ops(sb, d.fallThrough());
        sb.append(", \"jump\": ");
        ops(sb, d.jump());
        sb.append(", \"join\": ").append(d.join()).append(", \"stack\": ").append(d.stackDelta()).append('}');
    }

    private static void ops(StringBuilder sb, List<Op> ops) {
        sb.append('[');
        for (int k = 0; k < ops.size(); k++) {
            Op op = ops.get(k);
            sb.append(k == 0 ? "" : ", ").append('[').append(op.opcode()).append(", ").append(op.operand())
                    .append(", ").append(op.increment());
            if (op.nested() != null) {
                sb.append(", ");
                diamond(sb, op.nested());
            }
            sb.append(']');
        }
        sb.append(']');
    }

    private static void intMap(StringBuilder sb, Map<Integer, Integer> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(map).entrySet()) {
            sb.append(first ? "" : ", ").append('"').append(e.getKey()).append("\": ").append(e.getValue());
            first = false;
        }
        sb.append('}');
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---- reading

    private static MethodFacts method(Map<?, ?> m) {
        List<LoopHead> heads = new ArrayList<>();
        if (m.get("heads") instanceof List<?> hs) {
            for (Object o : hs) {
                List<?> h = (List<?>) o;
                List<?> slots = (List<?>) h.get(2);
                heads.add(new LoopHead(integer(h.get(0)), integer(h.get(1)),
                        slots.stream().mapToInt(LoopFactsJson::integer).toArray(), ((String) h.get(3)).toCharArray()));
            }
        }
        Map<Integer, Plan> byJump = new java.util.HashMap<>();
        if (m.get("plans") instanceof Map<?, ?> ps) {
            Map<Integer, Integer> backEdges = new java.util.HashMap<>();
            for (Map.Entry<?, ?> e : ps.entrySet()) {
                int ordinal = Integer.parseInt((String) e.getKey());
                Map<?, ?> p = (Map<?, ?>) e.getValue();
                if (p.containsKey("diamond")) {
                    byJump.put(ordinal, diamond((Map<?, ?>) p.get("diamond")));
                } else if (p.containsKey("loop")) {
                    Map<?, ?> l = (Map<?, ?>) p.get("loop");
                    byJump.put(ordinal, new CountingLoop(integer(l.get("i")), integer(l.get("step")),
                            integer(l.get("bound")), integer(l.get("boundConstant")), (Boolean) l.get("exitOnJump"),
                            intMap(l.get("steps")), intMap(l.get("constants")), (Boolean) l.get("pure"),
                            (Boolean) l.get("inclusive")));
                } else {
                    backEdges.put(ordinal, integer(p.get("backEdgeOf")));
                }
            }
            // The back edge refers to the same loop object as its head: the executor compares them by identity
            backEdges.forEach((ordinal, head) -> byJump.put(ordinal, new BackEdge((CountingLoop) byJump.get(head))));
        }
        Map<Integer, Integer> joins = m.containsKey("joins") ? intMap(m.get("joins")) : Map.of();
        return new MethodFacts((String) m.get("hash"), List.copyOf(heads), new MethodPlans(byJump, joins));
    }

    private static Diamond diamond(Map<?, ?> d) {
        return new Diamond(ops(d.get("fall")), ops(d.get("jump")), integer(d.get("join")), integer(d.get("stack")));
    }

    private static List<Op> ops(Object o) {
        List<Op> ops = new ArrayList<>();
        for (Object x : (List<?>) o) {
            List<?> op = (List<?>) x;
            Diamond nested = op.size() > 3 ? diamond((Map<?, ?>) op.get(3)) : null;
            ops.add(new Op(integer(op.get(0)), integer(op.get(1)), integer(op.get(2)), nested));
        }
        return List.copyOf(ops);
    }

    private static Map<Integer, Integer> intMap(Object o) {
        Map<Integer, Integer> map = new java.util.HashMap<>();
        ((Map<?, ?>) o).forEach((k, v) -> map.put(Integer.parseInt((String) k), integer(v)));
        return map;
    }

    private static int integer(Object o) {
        return ((Number) o).intValue();
    }

    /** A JSON parser for what {@link #write} produces: objects, arrays, strings, integers, booleans, null. */
    private static final class Parser {
        private final CharSequence s;
        private int at;

        Parser(CharSequence s) {
            this.s = s;
        }

        Object document() {
            Object v = value();
            space();
            if (at != s.length()) {
                throw error("trailing content");
            }
            return v;
        }

        private Object value() {
            space();
            if (at >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(at);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            space();
            if (peek() == '}') {
                at++;
                return map;
            }
            while (true) {
                space();
                String key = string();
                space();
                expect(':');
                map.put(key, value());
                space();
                if (peek() == ',') {
                    at++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            at++;
            space();
            if (peek() == ']') {
                at++;
                return list;
            }
            while (true) {
                list.add(value());
                space();
                if (peek() == ',') {
                    at++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(at++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(at++);
                    switch (e) {
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.subSequence(at, at + 4).toString(), 16));
                            at += 4;
                        }
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Long number() {
            int start = at;
            if (peek() == '-') {
                at++;
            }
            while (at < s.length() && Character.isDigit(s.charAt(at))) {
                at++;
            }
            if (start == at) {
                throw error("unexpected character");
            }
            return Long.parseLong(s.subSequence(start, at).toString());
        }

        private Object literal(String word, Object value) {
            if (!s.subSequence(at, Math.min(s.length(), at + word.length())).toString().equals(word)) {
                throw error("expected " + word);
            }
            at += word.length();
            return value;
        }

        private void space() {
            while (at < s.length() && Character.isWhitespace(s.charAt(at))) {
                at++;
            }
        }

        private char peek() {
            return at < s.length() ? s.charAt(at) : '\0';
        }

        private void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            at++;
        }

        private IllegalArgumentException error(String what) {
            return new IllegalArgumentException(what + " at " + at);
        }
    }
}
