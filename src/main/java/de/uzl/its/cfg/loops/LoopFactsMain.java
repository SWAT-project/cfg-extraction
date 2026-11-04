package de.uzl.its.cfg.loops;

import de.uzl.its.cfg.loops.LoopFacts.MethodFacts;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Writes the loop facts of every class on a classpath (directories and jars) to a file:
 *
 * <pre>
 * java -cp cfg-extractor.jar de.uzl.its.cfg.loops.LoopFactsMain &lt;out.json&gt; &lt;classpath entry&gt;...
 * </pre>
 *
 * Prints one line: the number of classes and methods, and how many methods have facts.
 */
public final class LoopFactsMain {

    private LoopFactsMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: LoopFactsMain <out.json> <classpath entry>...");
            System.exit(2);
        }
        int[] classes = {0};
        Map<String, MethodFacts> methods = analyze(List.of(args).subList(1, args.length), classes);
        write(methods, Path.of(args[0]));
        long withFacts = methods.values().stream().filter(f -> !f.isEmpty()).count();
        System.out.println("loop facts: " + classes[0] + " classes, " + methods.size() + " methods, " + withFacts
                + " with facts");
    }

    /** The facts of every class in the entries; counts the classes in {@code classes[0]}. */
    public static Map<String, MethodFacts> analyze(List<String> entries, int[] classes) throws IOException {
        return analyze(entries, null, classes);
    }

    /**
     * The facts of every class in the entries, skipping the directory {@code ignore} (may be null)
     * where it lies inside an entry: the executor dumps instrumented copies of the classes into its
     * log directory, which may be inside the target's class directory.
     */
    public static Map<String, MethodFacts> analyze(List<String> entries, Path ignore, int[] classes)
            throws IOException {
        Map<String, MethodFacts> methods = new HashMap<>();
        Path skip = ignore == null ? null : ignore.toAbsolutePath().normalize();
        for (String entry : entries) {
            if (entry.isEmpty()) {
                continue;
            }
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    for (Path p : (Iterable<Path>) files
                            .filter(f -> skip == null || path.startsWith(skip) || !f.startsWith(skip))
                            .filter(LoopFactsMain::isClassFile)::iterator) {
                        try (InputStream in = Files.newInputStream(p)) {
                            add(in, methods, classes);
                        }
                    }
                }
            } else if (Files.isRegularFile(path) && (entry.endsWith(".jar") || entry.endsWith(".zip"))) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    Enumeration<? extends ZipEntry> zes = zip.entries();
                    while (zes.hasMoreElements()) {
                        ZipEntry ze = zes.nextElement();
                        if (ze.getName().endsWith(".class") && !ze.getName().endsWith("module-info.class")
                                && !ze.getName().startsWith("META-INF/")) {
                            try (InputStream in = zip.getInputStream(ze)) {
                                add(in, methods, classes);
                            }
                        }
                    }
                }
            }
        }
        return methods;
    }

    /**
     * Writes the facts to {@code out}, atomically: an executor started meanwhile sees the whole
     * file or none.
     */
    public static void write(Map<String, MethodFacts> methods, Path out) throws IOException {
        Path dir = out.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, out.getFileName().toString(), ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                LoopFactsJson.write(methods, w);
            }
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static boolean isClassFile(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".class") && !name.equals("module-info.class");
    }

    private static void add(InputStream in, Map<String, MethodFacts> methods, int[] classes) throws IOException {
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(in).accept(cn, 0);
        } catch (RuntimeException e) {
            return; // not a class file ASM can read: the executor will not instrument it either
        }
        classes[0]++;
        // The first entry with a class wins, as on the JVM's classpath
        LoopFacts.analyzeClass(cn).forEach(methods::putIfAbsent);
    }
}
