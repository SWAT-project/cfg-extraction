package de.uzl.its.cfg.extractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.typehierarchy.TypeHierarchy;
import sootup.core.types.ClassType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Decides which handler catches an exception of a precisely known type.
 *
 * <p>The rule is Java's first-match-wins, recovered from the types alone: a handler catches a
 * {@code T} if it declares {@code T} or one of its superclasses, and of several such handlers the
 * one declaring the most specific type wins. javac rejects a catch clause that an earlier clause of
 * the same {@code try} already covers, so for two handlers in a subtype relation the subtype
 * necessarily comes first. That makes the order derivable, which matters because
 * {@code exceptionalSuccessors()} returns a plain HashMap and has already lost it.
 *
 * <p>The most specific handler is found by walking {@code T}'s superclass chain upwards and taking
 * the first type a handler is registered for. Exception types are classes, so the chain is linear
 * and the first hit is the most specific catching type. The same chain is written into the graph
 * for every {@code uncaught[T]} node, and the consumer resolves an unwinding exception against a
 * call site's handler map with exactly this walk, so the handlers the static
 * {@code EXCEPTIONAL_RETURN} edges point at and the handler the walk resumes at always agree.
 *
 * <p>Nested {@code try} blocks are the one case the types cannot settle: an inner
 * {@code catch (Exception e)} shadows an outer {@code catch (RuntimeException e)} for a
 * {@code RuntimeException}, yet the outer declares the more specific type. SootUp's handler map
 * holds both and no order, so this picks the outer one. It is a pre-existing limitation of the
 * guard wiring too and has not been observed in the benchmarks.
 */
public final class ExceptionHandlers {

    private static final Logger logger = LoggerFactory.getLogger(ExceptionHandlers.class);

    private ExceptionHandlers() {
    }

    /**
     * {@code type} followed by its superclasses, most specific first, ending at
     * {@code java.lang.Throwable} (or wherever the hierarchy stops knowing more).
     */
    public static List<ClassType> chainOf(ClassType type, TypeHierarchy hierarchy) {
        List<ClassType> chain = new ArrayList<>();
        chain.add(type);
        try {
            hierarchy.superClassesOf(type).forEach(chain::add);
        } catch (RuntimeException e) {
            // A type the hierarchy has never seen. The chain then only names the type itself, so a
            // handler for a supertype is missed -- which leaves the exception escaping further
            // than it does, never resuming somewhere it does not.
            logger.debug("Could not compute superclasses of {}: {}", type, e.getMessage());
        }
        return chain;
    }

    /** {@link #chainOf} as fully qualified names, the form written into the graph. */
    public static List<String> chainNamesOf(ClassType type, TypeHierarchy hierarchy) {
        return chainOf(type, hierarchy).stream()
                .map(ClassType::getFullyQualifiedName)
                .collect(Collectors.toList());
    }

    /**
     * The handler among {@code handlers} that catches an exception whose superclass chain is
     * {@code chain}, or null if none does and the exception leaves the method.
     */
    public static <H> H handlerFor(List<ClassType> chain, Map<ClassType, H> handlers) {
        for (ClassType type : chain) {
            H handler = handlers.get(type);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }

    /** {@link #handlerFor} for a single exception type. */
    public static <H> H handlerFor(ClassType type, Map<ClassType, H> handlers, TypeHierarchy hierarchy) {
        return handlerFor(chainOf(type, hierarchy), handlers);
    }
}
