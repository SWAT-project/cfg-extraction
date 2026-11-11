package de.uzl.its.cfg.staticcheck;

/**
 * The calls into SWAT's own classes that SWAT's semantic rewrites put into application code
 * ("prepared classes", see SWAT's {@code de.uzl.its.swat.prepare.Prepare}), and what each one does
 * as far as the analyses here are concerned.
 *
 * <p>SWAT's executor rewrites every application class before it instruments it: reference
 * comparisons become {@code UtilInstrumented.refEquals} and a jump on its result, string literals
 * and boxing become fresh objects marked with {@code RefIdentity.markDeInterned}, the SV-COMP
 * {@code Verifier} calls go to SWAT's own {@code Verifier} (each nondet value then passes through
 * {@code Witness.recordValue} and {@code Intrinsics.liftValue}), every {@code athrow} is preceded
 * by {@code Witness.recordThrow}, and {@code System.exit}, {@code Runtime.exit} and {@code
 * Runtime.halt} go to {@code SystemExit}. The extractor may be given those prepared classes. The
 * classes called are not on its classpath (they live in the executor's jar), so every such call is
 * a call into an unknown library class; this class says what it stands for.
 *
 * <p>Owner, name and descriptor are JVM internal names ({@code de/uzl/its/swat/...}). Anything of
 * SWAT's not listed here ({@code Socket}, the mock of {@code java.net.Socket}, and every method not
 * named) is {@link Kind#UNMODELLED}: an ordinary library call. Change this together with SWAT's
 * rewrites ({@code instrument/{nocache,refequality,svcomp}}).
 */
public final class SwatHelpers {

    /** The package prefix of SWAT's classes (internal name). */
    public static final String SWAT_PREFIX = "de/uzl/its/swat/";

    static final String VERIFIER = SWAT_PREFIX + "instrument/svcomp/Verifier";
    static final String SYSTEM_EXIT = SWAT_PREFIX + "instrument/svcomp/SystemExit";
    static final String WITNESS = SWAT_PREFIX + "witness/Witness";
    static final String INTRINSICS = SWAT_PREFIX + "instrument/Intrinsics";
    static final String UTIL = SWAT_PREFIX + "common/UtilInstrumented";
    static final String REF_IDENTITY = SWAT_PREFIX + "common/RefIdentity";

    /** What a call into SWAT's classes is. */
    public enum Kind {
        /** Not one of the helpers below: an ordinary library call. */
        UNMODELLED,
        /** {@code Verifier.nondetX(J)X}: SV-COMP's {@code Verifier.nondetX()}, an input. */
        NONDET,
        /** {@code Verifier.assume(Z)V}: SV-COMP's {@code Verifier.assume}, may end the run normally. */
        ASSUME,
        /** {@code SystemExit.exit(I)V}: {@code System.exit}. */
        EXIT,
        /** {@code SystemExit.runtimeExit/runtimeHalt(Runtime, I)V}: {@code Runtime.exit/halt} on the first argument. */
        RUNTIME_EXIT,
        /**
         * Bookkeeping invisible to the program: {@code Witness.recordValue}, {@code
         * Witness.recordThrow}, {@code RefIdentity.markDeInterned}. No effect on program state, no
         * exception, no call back into the application.
         */
        BOOKKEEPING,
        /** {@code Intrinsics.liftValue(XJ)X}: returns its first argument. */
        IDENTITY,
        /** {@code UtilInstrumented.refEquals(Object, Object)Z}: {@code a == b}; throws nothing, calls nothing back. */
        REF_EQUALS,
        /** {@code UtilInstrumented.intern(String)String}: {@code s.intern()} (throws if {@code s} is null). */
        INTERN
    }

    private SwatHelpers() {
    }

    /** Whether {@code owner} (internal name) is one of SWAT's classes. */
    public static boolean isSwat(String owner) {
        return owner.startsWith(SWAT_PREFIX);
    }

    /** What the call {@code owner.name(desc)} (internal names) is; UNMODELLED for anything else. */
    public static Kind kindOf(String owner, String name, String desc) {
        if (!isSwat(owner)) {
            return Kind.UNMODELLED;
        }
        switch (owner) {
            case VERIFIER:
                if (name.startsWith("nondet") && desc.startsWith("(J)") && !desc.endsWith("V")) return Kind.NONDET;
                if (name.equals("assume") && desc.equals("(Z)V")) return Kind.ASSUME;
                return Kind.UNMODELLED;
            case SYSTEM_EXIT:
                if (name.equals("exit") && desc.equals("(I)V")) return Kind.EXIT;
                if ((name.equals("runtimeExit") || name.equals("runtimeHalt")) && desc.equals("(Ljava/lang/Runtime;I)V")) {
                    return Kind.RUNTIME_EXIT;
                }
                return Kind.UNMODELLED;
            case WITNESS:
                if ((name.equals("recordValue") || name.equals("recordThrow")) && desc.endsWith(")V")) return Kind.BOOKKEEPING;
                return Kind.UNMODELLED;
            case REF_IDENTITY:
                return name.equals("markDeInterned") && desc.equals("(Ljava/lang/Object;)V") ? Kind.BOOKKEEPING : Kind.UNMODELLED;
            case INTRINSICS:
                return name.equals("liftValue") && isLift(desc) ? Kind.IDENTITY : Kind.UNMODELLED;
            case UTIL:
                if (name.equals("refEquals") && desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Z")) return Kind.REF_EQUALS;
                if (name.equals("intern") && desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) return Kind.INTERN;
                return Kind.UNMODELLED;
            default:
                return Kind.UNMODELLED;
        }
    }

    /** {@code (XJ)X}: a value and its nondet id, the value returned. */
    private static boolean isLift(String desc) {
        if (!desc.startsWith("(")) return false;
        int close = desc.indexOf(')');
        if (close < 0 || !desc.startsWith("J", close - 1)) return false;
        String value = desc.substring(1, close - 1);
        return value.equals(desc.substring(close + 1)) && !value.isEmpty();
    }

    /** As {@link #kindOf(String, String, String)}, for a class name in dotted form (SootUp). */
    public static Kind kindOfDotted(String className, String name, String desc) {
        return kindOf(className.replace('.', '/'), name, desc);
    }

    /**
     * Whether the call can neither throw nor run application code: a helper SWAT added that the
     * original program does not have a call for (bookkeeping, liftValue, refEquals).
     */
    public static boolean isTransparent(Kind kind) {
        return kind == Kind.BOOKKEEPING || kind == Kind.IDENTITY || kind == Kind.REF_EQUALS;
    }

    /** Whether the call stands for a call the analyses model and that never calls back into the application. */
    public static boolean isModelled(Kind kind) {
        return kind != Kind.UNMODELLED;
    }
}
