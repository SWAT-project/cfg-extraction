package de.uzl.its.cfg.loops;

import de.uzl.its.cfg.loops.LoopFacts.MethodFacts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Loop facts carried by the class files themselves: an invisible method annotation
 *
 * <pre>@de.uzl.its.swat.facts.LoopFacts(version = LoopFacts.VERSION, hash = "...", facts = {"&lt;json&gt;", ...})</pre>
 *
 * on every method with code, written by the static analysis into SWAT's prepared classes and read
 * by SWAT's executor when it instruments the method. {@code facts} is the method's entry of the
 * facts file ({@link LoopFactsJson#writeMethod}), split into chunks because a class-file string
 * holds at most 64 KiB. The annotation type does not exist as a class; the JVM ignores invisible
 * annotations. Its name is in SWAT's namespace, which the executor's shadow jar does not relocate.
 *
 * <p>As with the file, the executor uses the facts only if {@code hash} matches the code it
 * instruments, and computes them otherwise. Methods without loop facts are annotated too (hash
 * alone), so that the executor knows they were analysed.
 */
public final class LoopFactsAnnotations {

    /** Descriptor of the annotation. Built at run time, so that no relocation can rewrite it. */
    public static final String DESC = "Lde/uzl/its/".concat("swat/facts/LoopFacts;");

    /** Characters per chunk of the facts (a class-file string holds at most 65535 bytes). */
    private static final int CHUNK = 16_000;

    private LoopFactsAnnotations() {}

    /**
     * The facts the annotation of a method carries, or null if it has none, of another version, or
     * a malformed one. The caller still has to check the hash against the code.
     */
    public static MethodFacts read(MethodNode mn) {
        if (mn.invisibleAnnotations == null) {
            return null;
        }
        for (AnnotationNode a : mn.invisibleAnnotations) {
            if (DESC.equals(a.desc)) {
                return fromValues(a.values);
            }
        }
        return null;
    }

    private static MethodFacts fromValues(List<Object> values) {
        if (values == null) {
            return null;
        }
        Integer version = null;
        String hash = null;
        List<?> chunks = null;
        for (int i = 0; i + 1 < values.size(); i += 2) {
            Object name = values.get(i);
            Object value = values.get(i + 1);
            if ("version".equals(name) && value instanceof Integer v) {
                version = v;
            } else if ("hash".equals(name) && value instanceof String h) {
                hash = h;
            } else if ("facts".equals(name) && value instanceof List<?> l) {
                chunks = l;
            }
        }
        if (version == null || version != LoopFacts.VERSION || hash == null || chunks == null) {
            return null;
        }
        StringBuilder json = new StringBuilder();
        for (Object c : chunks) {
            json.append((String) c);
        }
        try {
            MethodFacts f = LoopFactsJson.readMethod(json);
            return f.hash().equals(hash) ? f : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Adds the facts of every method with code to a class ({@link LoopFacts#analyzeClass}),
     * replacing facts annotated before. Everything else is copied unchanged.
     *
     * @return the annotated class, and how many methods got facts in {@code counts[0]} (of those,
     *     how many are not empty in {@code counts[1]})
     */
    public static byte[] annotate(byte[] cls, int[] counts) {
        ClassReader cr = new ClassReader(cls);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_FRAMES);
        Map<String, MethodFacts> facts = LoopFacts.analyzeClass(cn);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                MethodFacts f = facts.get(LoopFacts.key(cn.name, name, desc));
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                        return DESC.equals(d) ? null : super.visitAnnotation(d, visible);
                    }

                    @Override
                    public void visitCode() {
                        if (f != null) {
                            write(super.visitAnnotation(DESC, false), f);
                            counts[0]++;
                            if (!f.isEmpty()) {
                                counts[1]++;
                            }
                        }
                        super.visitCode();
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    private static void write(AnnotationVisitor av, MethodFacts f) {
        av.visit("version", LoopFacts.VERSION);
        av.visit("hash", f.hash());
        String json = LoopFactsJson.writeMethod(f);
        AnnotationVisitor chunks = av.visitArray("facts");
        for (int i = 0; i < json.length(); i += CHUNK) {
            chunks.visit(null, json.substring(i, Math.min(json.length(), i + CHUNK)));
        }
        chunks.visitEnd();
        av.visitEnd();
    }

    /**
     * Annotates every class file in a directory in place. Each file is replaced atomically, so a
     * JVM loading the class meanwhile reads either version. A class that cannot be read is left as
     * it is (the executor computes its facts).
     *
     * @return {classes annotated, methods with facts annotated, of those not empty}
     */
    public static int[] annotateDirectory(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p)).sorted().toList();
        }
        int[] counts = new int[3];
        List<String> failed = new ArrayList<>();
        for (Path f : files) {
            if (f.getFileName().toString().equals("module-info.class")) {
                continue;
            }
            byte[] annotated;
            int[] c = new int[2];
            try {
                annotated = annotate(Files.readAllBytes(f), c);
            } catch (RuntimeException e) {
                failed.add(f.toString());
                continue;
            }
            Path tmp = Files.createTempFile(f.getParent(), ".facts", ".tmp");
            Files.write(tmp, annotated);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            counts[0]++;
            counts[1] += c[0];
            counts[2] += c[1];
        }
        return counts;
    }
}
