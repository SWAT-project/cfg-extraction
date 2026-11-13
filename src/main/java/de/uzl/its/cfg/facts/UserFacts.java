package de.uzl.its.cfg.facts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Hand-written facts (SWAT's user facts): the annotations of SWAT's annotations module in the
 * program's class files, and the library facts of a stub file next to them. Read only with
 * {@code --user-facts}; SWAT never passes it for SV-COMP.
 *
 * <ul>
 *   <li>{@code @Irrelevant} on a class, method or constructor, and the statements between
 *       {@code Swat.beginIrrelevant()} and {@code Swat.endIrrelevant()}: their branches cannot
 *       affect a target. The graph marks their nodes ({@code userFact}, named as SWAT's executor
 *       names the fact), and SWAT's relevance analysis checks and uses them.
 *   <li>{@code @Target} on a method: reaching it is a violation. Its entry is an assertion point.
 *   <li>{@code Swat.summary()} (or SWAT's replacement {@code UserFacts.summary(String)} in prepared
 *       classes): returns true while SWAT runs with user facts, so the side of the {@code if} that
 *       runs the loop is dead.
 *   <li>{@code @NotCalledBack} on a method: library code never calls it, so it is no callback.
 *   <li>Library facts ({@link LibraryFacts}, file {@value #STUB_FILE} at the root of a classpath
 *       directory): what a library method reads and writes, and whether it may call back.
 * </ul>
 *
 * Methods are keyed as {@code owner.name(desc)} with the owner's internal name.
 */
public final class UserFacts {

    public static final String PACKAGE = "de/uzl/its/".concat("swat/annotations/");
    public static final String IRRELEVANT = "L" + PACKAGE + "Irrelevant;";
    public static final String TARGET = "L" + PACKAGE + "Target;";
    public static final String NOT_CALLED_BACK = "L" + PACKAGE + "NotCalledBack;";
    public static final String SWAT = PACKAGE + "Swat";
    /** SWAT's replacement of {@code Swat.summary()} in prepared classes. */
    public static final String SWAT_USER_FACTS = "de/uzl/its/".concat("swat/facts/UserFacts");

    /** The library facts file, at the root of a classpath directory. */
    public static final String STUB_FILE = "swat-library-facts.txt";

    /**
     * A {@code Swat.beginIrrelevant/endIrrelevant} region: the instructions from the begin call to
     * the end call in bytecode order, or to the end of the method without one (as SWAT's executor
     * applies it). By line: {@code lines} have only instructions in the region, {@code nearLines}
     * have some in it and some outside (the graph cannot tell those statements apart by line).
     */
    public record Region(String method, int beginLine, java.util.Set<Integer> lines, java.util.Set<Integer> nearLines,
            String fact) {}

    /** The facts in effect: none unless {@link #install} was called. */
    private static UserFacts current = new UserFacts();

    /** method key or internal class name -> the irrelevance fact covering it. */
    private final Map<String, String> irrelevant = new LinkedHashMap<>();
    private final List<Region> regions = new ArrayList<>();
    /** method key -> the target's description. */
    private final Map<String, String> targets = new LinkedHashMap<>();
    private final Set<String> notCalledBack = new LinkedHashSet<>();
    private LibraryFacts library = LibraryFacts.NONE;
    private boolean enabled = false;

    public static UserFacts current() {
        return current;
    }

    public static void install(UserFacts facts) {
        current = facts;
    }

    public boolean enabled() {
        return enabled;
    }

    public static String key(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    /** The irrelevance fact covering a whole method (its own, or its class's), or null. */
    public String irrelevantFact(String owner, String name, String desc) {
        String fact = irrelevant.get(key(owner, name, desc));
        return fact != null ? fact : irrelevant.get(owner);
    }

    public List<Region> regions(String owner, String name, String desc) {
        String k = key(owner, name, desc);
        return regions.stream().filter(r -> r.method().equals(k)).toList();
    }

    public String target(String owner, String name, String desc) {
        return targets.get(key(owner, name, desc));
    }

    public boolean isNotCalledBack(String owner, String name, String desc) {
        return notCalledBack.contains(key(owner, name, desc));
    }

    public LibraryFacts library() {
        return library;
    }

    /** Every fact read, for the graph's metadata. */
    public Map<String, Object> describe() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("irrelevant", new ArrayList<>(new LinkedHashSet<>(irrelevant.values())));
        d.put("regions", regions.stream().map(Region::fact).toList());
        d.put("targets", new ArrayList<>(targets.keySet()));
        d.put("notCalledBack", new ArrayList<>(notCalledBack));
        d.put("library", library.describe());
        return d;
    }

    /** Reads the facts of every class on the classpath (directories and jars) and the stub files of its directories. */
    public static UserFacts read(List<Path> classpath) throws IOException {
        UserFacts facts = new UserFacts();
        facts.enabled = true;
        List<LibraryFacts> stubs = new ArrayList<>();
        for (Path entry : classpath) {
            if (Files.isRegularFile(entry) && (entry.toString().endsWith(".jar") || entry.toString().endsWith(".zip"))) {
                // Annotations in a jar's classes count too (no stub file there)
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(entry.toFile())) {
                    for (java.util.zip.ZipEntry e : java.util.Collections.list(zip.entries())) {
                        if (e.getName().endsWith(".class") && !e.isDirectory()) {
                            try (InputStream in = zip.getInputStream(e)) {
                                facts.readClass(in.readAllBytes());
                            } catch (RuntimeException ex) {
                                // an unreadable class has no facts
                            }
                        }
                    }
                }
                continue;
            }
            if (!Files.isDirectory(entry)) {
                continue;
            }
            Path stub = entry.resolve(STUB_FILE);
            if (Files.isRegularFile(stub)) {
                stubs.add(LibraryFacts.parse(Files.readAllLines(stub), stub.toString()));
            }
            List<Path> files;
            try (Stream<Path> walk = Files.walk(entry)) {
                files = walk.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p)).sorted().toList();
            }
            for (Path f : files) {
                try (InputStream in = Files.newInputStream(f)) {
                    facts.readClass(in.readAllBytes());
                } catch (RuntimeException e) {
                    // an unreadable class has no facts
                }
            }
        }
        facts.library = LibraryFacts.merge(stubs);
        return facts;
    }

    void readClass(byte[] bytes) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!text.contains(PACKAGE) && !text.contains(SWAT_USER_FACTS)) {
            return;
        }
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
        if (has(cn.invisibleAnnotations, IRRELEVANT) || has(cn.visibleAnnotations, IRRELEVANT)) {
            irrelevant.put(cn.name, "@Irrelevant " + cn.name); // as SWAT's executor names it
        }
        for (MethodNode mn : cn.methods) {
            String k = key(cn.name, mn.name, mn.desc);
            if (has(mn.invisibleAnnotations, IRRELEVANT) || has(mn.visibleAnnotations, IRRELEVANT)) {
                irrelevant.put(k, "@Irrelevant " + k);
            }
            AnnotationNode target = find(mn.invisibleAnnotations, TARGET);
            if (target != null && !mn.name.equals("<init>") && !mn.name.equals("<clinit>")) {
                targets.put(k, "@Target " + k);
            }
            if (has(mn.invisibleAnnotations, NOT_CALLED_BACK) || has(mn.visibleAnnotations, NOT_CALLED_BACK)) {
                notCalledBack.add(k);
            }
            int line = -1;
            String fact = null;
            int begin = -1;
            java.util.Set<Integer> in = new java.util.TreeSet<>();
            java.util.Set<Integer> outside = new java.util.TreeSet<>(); // lines with instructions outside any region
            for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof LineNumberNode l) {
                    line = l.line;
                    continue;
                }
                if (n.getOpcode() < 0) {
                    continue; // labels, frames
                }
                boolean marker = n instanceof MethodInsnNode m && m.getOpcode() == Opcodes.INVOKESTATIC
                        && m.owner.equals(SWAT) && m.desc.equals("()V")
                        && (m.name.equals("beginIrrelevant") || m.name.equals("endIrrelevant"));
                if (marker) {
                    if (fact != null) {
                        regions.add(new Region(k, begin, in, java.util.Set.of(), fact));
                    }
                    fact = null;
                    in = new java.util.TreeSet<>();
                    if (((MethodInsnNode) n).name.equals("beginIrrelevant")) {
                        begin = line;
                        fact = "Swat.beginIrrelevant " + k + " line " + line; // as SWAT's executor names it
                    }
                    outside.add(line); // the marker call itself is not in the region
                    continue;
                }
                (fact != null ? in : outside).add(line);
            }
            if (fact != null) {
                regions.add(new Region(k, begin, in, java.util.Set.of(), fact)); // no end: to the end of the method
            }
            // A line with instructions both in and outside a region is only near it
            for (int i = 0; i < regions.size(); i++) {
                Region r = regions.get(i);
                if (!r.method().equals(k)) {
                    continue;
                }
                java.util.Set<Integer> strict = new java.util.TreeSet<>(r.lines());
                strict.removeAll(outside);
                java.util.Set<Integer> near = new java.util.TreeSet<>(r.lines());
                near.retainAll(outside);
                regions.set(i, new Region(k, r.beginLine(), strict, near, r.fact()));
            }
        }
    }

    private static boolean has(List<AnnotationNode> annotations, String desc) {
        return find(annotations, desc) != null;
    }

    private static AnnotationNode find(List<AnnotationNode> annotations, String desc) {
        if (annotations != null) {
            for (AnnotationNode a : annotations) {
                if (a.desc.equals(desc)) {
                    return a;
                }
            }
        }
        return null;
    }
}
