package de.uzl.its.cfg.loops

import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodNode
import spock.lang.Specification

class LoopHeadsTest extends Specification {

    /** static void m(int n, long l) { int x = 0; while (x < n) { x++; } } */
    private static MethodNode countingLoop(boolean valueOnStack) {
        MethodNode mn = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "m", "(IJ)V", null, null)
        Label head = new Label()
        Label end = new Label()
        mn.visitCode()
        mn.visitInsn(Opcodes.ICONST_0)
        mn.visitVarInsn(Opcodes.ISTORE, 3)
        if (valueOnStack) {
            mn.visitInsn(Opcodes.ICONST_0)
        }
        mn.visitLabel(head)
        mn.visitVarInsn(Opcodes.ILOAD, 3)
        mn.visitVarInsn(Opcodes.ILOAD, 0)
        mn.visitJumpInsn(Opcodes.IF_ICMPGE, end)
        mn.visitIincInsn(3, 1)
        mn.visitJumpInsn(Opcodes.GOTO, head)
        mn.visitLabel(end)
        if (valueOnStack) {
            mn.visitInsn(Opcodes.POP)
        }
        mn.visitInsn(Opcodes.RETURN)
        mn.visitMaxs(3, 4)
        mn.visitEnd()
        return mn
    }

    def "finds the target of a backward jump and the locals defined there"() {
        when:
        def heads = LoopHeads.analyze("Fixture", countingLoop(false))

        then:
        heads.size() == 1
        def head = heads.first()
        head.ordinal() == 0
        head.at() == 2 // after ICONST_0, ISTORE
        // slot 1 (a long parameter) is never read: dead, so not hashed
        head.slots() as List == [0, 3]
        head.kinds() as List == ['I' as char, 'I' as char]
    }

    /** {@code static void f(int p) { int i = 0, t = 5; head: while (i < 3) { [try] t = 7; use(t); i++ } } } */
    static MethodNode overwritten(boolean handlerReadsT) {
        MethodNode mn = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "f", "(I)V", null, null)
        Label head = new Label(), end = new Label(), handler = new Label()
        mn.visitCode()
        mn.visitInsn(Opcodes.ICONST_0); mn.visitVarInsn(Opcodes.ISTORE, 1)
        mn.visitInsn(Opcodes.ICONST_5); mn.visitVarInsn(Opcodes.ISTORE, 2)
        mn.visitLabel(head)
        mn.visitVarInsn(Opcodes.ILOAD, 1); mn.visitInsn(Opcodes.ICONST_3); mn.visitJumpInsn(Opcodes.IF_ICMPGE, end)
        mn.visitVarInsn(Opcodes.ILOAD, 1); mn.visitInsn(Opcodes.ICONST_1); mn.visitInsn(Opcodes.IDIV); mn.visitInsn(Opcodes.POP)
        mn.visitIntInsn(Opcodes.BIPUSH, 7); mn.visitVarInsn(Opcodes.ISTORE, 2)
        mn.visitVarInsn(Opcodes.ILOAD, 2); mn.visitInsn(Opcodes.POP)
        mn.visitIincInsn(1, 1)
        mn.visitJumpInsn(Opcodes.GOTO, head)
        mn.visitLabel(end)
        mn.visitInsn(Opcodes.RETURN)
        if (handlerReadsT) {
            mn.visitLabel(handler)
            mn.visitInsn(Opcodes.POP)
            mn.visitVarInsn(Opcodes.ILOAD, 2); mn.visitInsn(Opcodes.POP)
            mn.visitInsn(Opcodes.RETURN)
            mn.visitTryCatchBlock(head, end, handler, "java/lang/ArithmeticException")
        }
        mn.visitMaxs(2, 3)
        mn.visitEnd()
        return mn
    }

    def "a local written again in the body before any read is dead at the head"() {
        expect:
        // p (slot 0) is never read, t (slot 2) is overwritten before its read
        LoopHeads.analyze("Fixture3", overwritten(false)).first().slots() as List == [1]
    }

    def "a local an exception handler may read is live at the head"() {
        expect:
        // the division may throw before t is overwritten, and the handler reads t
        LoopHeads.analyze("Fixture4", overwritten(true)).first().slots() as List == [1, 2]
    }

    def "skips a head with values on the operand stack"() {
        expect:
        LoopHeads.analyze("Fixture2", countingLoop(true)).isEmpty()
    }

    def "a method without backward jumps has no heads"() {
        given:
        MethodNode mn = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "f", "(I)I", null, null)
        Label skip = new Label()
        mn.visitCode()
        mn.visitVarInsn(Opcodes.ILOAD, 0)
        mn.visitJumpInsn(Opcodes.IFEQ, skip)
        mn.visitInsn(Opcodes.ICONST_1)
        mn.visitInsn(Opcodes.IRETURN)
        mn.visitLabel(skip)
        mn.visitInsn(Opcodes.ICONST_0)
        mn.visitInsn(Opcodes.IRETURN)
        mn.visitMaxs(1, 1)
        mn.visitEnd()

        expect:
        LoopHeads.analyze("Fixture3", mn).isEmpty()
    }
}
