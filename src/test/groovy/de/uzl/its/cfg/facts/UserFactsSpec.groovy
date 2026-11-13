package de.uzl.its.cfg.facts

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import spock.lang.Specification

class UserFactsSpec extends Specification {

    def "parses library facts, the most specific entry first"() {
        given:
        def facts = LibraryFacts.parse([
                "# comment",
                "java.lang.StringBuilder.append(char)  reads=SB writes=SB nocallback",
                "java.lang.StringBuilder.append(*)     reads=SB writes=SB,Other",
                "java.lang.Integer.valueOf(int) pure",
                ""], "stub")

        expect:
        facts.families() == ["Other", "SB"] as Set
        def exact = facts.lookup("java.lang.StringBuilder", "append", ["char"])
        exact.noCallback() && exact.writes() == ["SB"] as Set
        def wild = facts.lookup("java.lang.StringBuilder", "append", ["java.lang.Object"])
        !wild.noCallback() && wild.writes() == ["SB", "Other"] as Set
        facts.lookup("java.lang.Integer", "valueOf", ["int"]).noCallback()
        facts.lookup("java.lang.Integer", "valueOf", ["java.lang.String"]) == null
        facts.used().size() == 3
    }

    def "rejects a malformed line rather than reading part of the file"() {
        when:
        LibraryFacts.parse([line], "stub")

        then:
        thrown(IllegalArgumentException)

        where:
        line << ["java.lang.StringBuilder.append reads=SB", "x.y(int) frobs", "x.y(int) pure writes=A",
                 "x.y(int) reads=", "noowner(int) pure"]
    }

    def "reads the annotations and the regions, named as SWAT's executor names them"() {
        given:
        ClassWriter cw = new ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "app/C", null, "java/lang/Object", null)
        def m = cw.visitMethod(Opcodes.ACC_STATIC, "log", "(I)V", null, null)
        m.visitAnnotation(UserFacts.IRRELEVANT, false).visitEnd()
        m.visitCode(); m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 1); m.visitEnd()
        m = cw.visitMethod(Opcodes.ACC_STATIC, "sink", "()V", null, null)
        m.visitAnnotation(UserFacts.TARGET, false).visitEnd()
        m.visitAnnotation(UserFacts.NOT_CALLED_BACK, false).visitEnd()
        m.visitCode(); m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 0); m.visitEnd()
        // line 10: begin; 11: in the region; 12: in it, then end, then outside; 13: a second region
        // without an end, to the end of the method
        m = cw.visitMethod(Opcodes.ACC_STATIC, "main", "()V", null, null)
        m.visitCode()
        def l1 = new org.objectweb.asm.Label(), l2 = new org.objectweb.asm.Label()
        def l3 = new org.objectweb.asm.Label(), l4 = new org.objectweb.asm.Label()
        m.visitLabel(l1); m.visitLineNumber(10, l1)
        m.visitMethodInsn(Opcodes.INVOKESTATIC, UserFacts.SWAT, "beginIrrelevant", "()V", false)
        m.visitLabel(l2); m.visitLineNumber(11, l2)
        m.visitInsn(Opcodes.ICONST_0); m.visitInsn(Opcodes.POP)
        m.visitLabel(l3); m.visitLineNumber(12, l3)
        m.visitInsn(Opcodes.ICONST_1); m.visitInsn(Opcodes.POP)
        m.visitMethodInsn(Opcodes.INVOKESTATIC, UserFacts.SWAT, "endIrrelevant", "()V", false)
        m.visitInsn(Opcodes.ICONST_2); m.visitInsn(Opcodes.POP)
        m.visitLabel(l4); m.visitLineNumber(13, l4)
        m.visitMethodInsn(Opcodes.INVOKESTATIC, UserFacts.SWAT, "beginIrrelevant", "()V", false)
        m.visitInsn(Opcodes.ICONST_3); m.visitInsn(Opcodes.POP)
        m.visitInsn(Opcodes.RETURN); m.visitMaxs(1, 0); m.visitEnd()
        cw.visitEnd()
        def facts = new UserFacts()

        when:
        facts.readClass(cw.toByteArray())

        then:
        facts.irrelevantFact("app/C", "log", "(I)V") == "@Irrelevant app/C.log(I)V"
        facts.irrelevantFact("app/C", "main", "()V") == null
        facts.target("app/C", "sink", "()V") == "@Target app/C.sink()V"
        facts.isNotCalledBack("app/C", "sink", "()V")
        facts.regions("app/C", "main", "()V") == [
                new UserFacts.Region("app/C.main()V", 10, [11] as Set, [12] as Set, "Swat.beginIrrelevant app/C.main()V line 10"),
                new UserFacts.Region("app/C.main()V", 13, [] as Set, [13] as Set, "Swat.beginIrrelevant app/C.main()V line 13")]
    }
}
