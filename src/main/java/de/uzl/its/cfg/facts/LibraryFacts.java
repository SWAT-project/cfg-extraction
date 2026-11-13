package de.uzl.its.cfg.facts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Hand-written facts about library methods, from a stub file ({@value UserFacts#STUB_FILE}), one
 * method per line:
 *
 * <pre>
 * # comment
 * java.lang.StringBuilder.append(char)   reads=StringBuilder writes=StringBuilder nocallback
 * java.util.HashMap.get(java.lang.Object) reads=HashMap
 * java.lang.Integer.valueOf(*)            pure
 * </pre>
 *
 * The parameter types are Java names, comma-separated; {@code *} matches every overload. What
 * follows says what the method may do:
 *
 * <ul>
 *   <li>{@code reads=F,...} and {@code writes=F,...}: the library state it may read and write, by
 *       family. A family is a name for part of the library state (say, the contents of every
 *       StringBuilder); the extractor turns it into the location {@code g:lib:F}. A method with
 *       facts touches no other library state; a library method without facts may touch all of it,
 *       every family included ({@code g:lib} and every {@code g:lib:F}).
 *   <li>{@code arrays}: it may also read and write the arrays it is given (as a method without
 *       facts may). Without it, it touches none.
 *   <li>{@code nocallback}: it never calls application code (no {@code toString()}, {@code
 *       equals}, comparator or lambda of the application).
 *   <li>{@code pure}: only its arguments flow into its result; it touches no state and calls
 *       nothing back.
 * </ul>
 *
 * Arguments always flow into the result. The facts are trusted: a wrong one can hide a violation.
 * SWAT reports a SAFE verdict as assuming the facts that the graph used.
 */
public final class LibraryFacts {

    /** The location of a family of library state. */
    public static String location(String family) {
        return "g:lib:" + family;
    }

    /**
     * The facts of one library method.
     *
     * @param text the line it came from, which names it in SWAT's verdict
     */
    public record Entry(String owner, String name, List<String> params, Set<String> reads, Set<String> writes,
            boolean arrays, boolean noCallback, String text) {
        boolean matches(String owner, String name, List<String> params) {
            return this.owner.equals(owner) && this.name.equals(name)
                    && (this.params == null || this.params.equals(params));
        }
    }

    public static final LibraryFacts NONE = new LibraryFacts(List.of());

    private final List<Entry> entries;
    /** The entries by owner and name, and every family named: built once, looked up per call. */
    private final Map<String, List<Entry>> byMethod = new java.util.HashMap<>();
    private final Set<String> families = new TreeSet<>();
    private final Set<String> used = new TreeSet<>();

    private LibraryFacts(List<Entry> entries) {
        this.entries = entries;
        for (Entry e : entries) {
            byMethod.computeIfAbsent(e.owner() + "." + e.name(), k -> new ArrayList<>()).add(e);
            families.addAll(e.reads());
            families.addAll(e.writes());
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Every family the facts name (sorted). */
    public Set<String> families() {
        return java.util.Collections.unmodifiableSet(families);
    }

    /**
     * The facts of a library method ({@code owner} with dots, parameter types as Java names), or
     * null. The most specific entry wins (an exact parameter list before {@code *}); a match is
     * recorded as used.
     */
    public Entry lookup(String owner, String name, List<String> params) {
        Entry wildcard = null;
        for (Entry e : byMethod.getOrDefault(owner + "." + name, List.of())) {
            if (e.matches(owner, name, params)) {
                if (e.params() != null) {
                    used.add(e.text());
                    return e;
                }
                if (wildcard == null) {
                    wildcard = e;
                }
            }
        }
        if (wildcard != null) {
            used.add(wildcard.text());
        }
        return wildcard;
    }

    /** The entries a lookup returned so far. */
    public Set<String> used() {
        return used;
    }

    Map<String, Object> describe() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("entries", entries.size());
        d.put("used", new ArrayList<>(used));
        return d;
    }

    static LibraryFacts merge(Collection<LibraryFacts> all) {
        List<Entry> entries = new ArrayList<>();
        all.forEach(f -> entries.addAll(f.entries));
        return entries.isEmpty() ? NONE : new LibraryFacts(entries);
    }

    /**
     * Parses a stub file.
     *
     * @throws IllegalArgumentException for a malformed line (a fact that is misread could hide a
     *     violation, so the file is rejected rather than read in part)
     */
    public static LibraryFacts parse(List<String> lines, String source) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int hash = line.indexOf('#');
            String text = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (text.isEmpty()) {
                continue;
            }
            String where = source + ":" + (i + 1);
            int open = text.indexOf('(');
            int close = text.indexOf(')', open + 1);
            if (open < 0 || close < 0) {
                throw new IllegalArgumentException(where + ": expected owner.name(params): " + text);
            }
            String qualified = text.substring(0, open).trim();
            int dot = qualified.lastIndexOf('.');
            if (dot <= 0 || dot == qualified.length() - 1) {
                throw new IllegalArgumentException(where + ": expected owner.name(params): " + text);
            }
            String owner = qualified.substring(0, dot);
            String name = qualified.substring(dot + 1);
            String paramText = text.substring(open + 1, close).trim();
            List<String> params;
            if (paramText.equals("*")) {
                params = null;
            } else if (paramText.isEmpty()) {
                params = List.of();
            } else {
                params = new ArrayList<>();
                for (String p : paramText.split(",")) {
                    params.add(p.trim());
                }
            }
            Set<String> reads = new LinkedHashSet<>();
            Set<String> writes = new LinkedHashSet<>();
            boolean arrays = false;
            boolean noCallback = false;
            boolean pure = false;
            for (String word : text.substring(close + 1).trim().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                if (word.startsWith("reads=")) {
                    families(word.substring(6), reads, where);
                } else if (word.startsWith("writes=")) {
                    families(word.substring(7), writes, where);
                } else if (word.equals("arrays")) {
                    arrays = true;
                } else if (word.equals("nocallback")) {
                    noCallback = true;
                } else if (word.equals("pure")) {
                    pure = true;
                } else {
                    throw new IllegalArgumentException(where + ": unknown word '" + word + "'");
                }
            }
            if (pure && (!reads.isEmpty() || !writes.isEmpty() || arrays)) {
                throw new IllegalArgumentException(where + ": a pure method touches no state");
            }
            entries.add(new Entry(owner, name, params == null ? null : List.copyOf(params), Set.copyOf(reads),
                    Set.copyOf(writes), arrays, noCallback || pure, text.replaceAll("\\s+", " ")));
        }
        return new LibraryFacts(entries);
    }

    private static void families(String list, Set<String> into, String where) {
        for (String f : list.split(",")) {
            if (f.isBlank() || !f.matches("[A-Za-z0-9_.$]+")) {
                throw new IllegalArgumentException(where + ": bad family '" + f + "'");
            }
            into.add(f);
        }
    }
}
