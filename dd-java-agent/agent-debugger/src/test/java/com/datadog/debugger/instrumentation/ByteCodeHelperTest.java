package com.datadog.debugger.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class ByteCodeHelperTest {

  @Test
  public void insn() {
    assertEquals(1, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.ACONST_NULL)));
    assertEquals(2, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.LCONST_0)));
    assertEquals(-1, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.IALOAD)));
    assertEquals(-3, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.IASTORE)));
    assertEquals(-4, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.LASTORE)));
    assertEquals(-1, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.POP)));
    assertEquals(-2, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.POP2)));
    assertEquals(1, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.DUP)));
    assertEquals(2, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.DUP2)));
    assertEquals(0, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.INEG)));
    assertEquals(-1, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.IADD)));
    assertEquals(-2, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.LADD)));
    assertEquals(1, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.I2L)));
    assertEquals(-1, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.L2I)));
    assertEquals(-3, ByteCodeHelper.adjustStackUsage(new InsnNode(Opcodes.LCMP)));
  }

  @Test
  public void intInsn() {
    assertEquals(1, ByteCodeHelper.adjustStackUsage(new IntInsnNode(Opcodes.BIPUSH, 0)));
    assertEquals(0, ByteCodeHelper.adjustStackUsage(new IntInsnNode(Opcodes.NEWARRAY, 0)));
  }

  @Test
  public void varInsn() {
    assertEquals(1, ByteCodeHelper.adjustStackUsage(new VarInsnNode(Opcodes.ILOAD, 0)));
    assertEquals(2, ByteCodeHelper.adjustStackUsage(new VarInsnNode(Opcodes.LLOAD, 0)));
    assertEquals(-1, ByteCodeHelper.adjustStackUsage(new VarInsnNode(Opcodes.ISTORE, 0)));
    assertEquals(-2, ByteCodeHelper.adjustStackUsage(new VarInsnNode(Opcodes.LSTORE, 0)));
  }

  @Test
  public void typeInsn() {
    assertEquals(1, ByteCodeHelper.adjustStackUsage(new TypeInsnNode(Opcodes.NEW, "")));
    assertEquals(0, ByteCodeHelper.adjustStackUsage(new TypeInsnNode(Opcodes.ANEWARRAY, "")));
    assertEquals(0, ByteCodeHelper.adjustStackUsage(new TypeInsnNode(Opcodes.CHECKCAST, "")));
    assertEquals(0, ByteCodeHelper.adjustStackUsage(new TypeInsnNode(Opcodes.INSTANCEOF, "")));
  }

  @Test
  public void fieldInsn() {
    assertEquals(
        0, ByteCodeHelper.adjustStackUsage(new FieldInsnNode(Opcodes.GETFIELD, "", "", "I")));
    assertEquals(
        1, ByteCodeHelper.adjustStackUsage(new FieldInsnNode(Opcodes.GETFIELD, "", "", "J")));
    assertEquals(
        -2, ByteCodeHelper.adjustStackUsage(new FieldInsnNode(Opcodes.PUTFIELD, "", "", "I")));
    assertEquals(
        -3, ByteCodeHelper.adjustStackUsage(new FieldInsnNode(Opcodes.PUTFIELD, "", "", "J")));
    assertEquals(
        1, ByteCodeHelper.adjustStackUsage(new FieldInsnNode(Opcodes.GETSTATIC, "", "", "I")));
    assertEquals(
        2, ByteCodeHelper.adjustStackUsage(new FieldInsnNode(Opcodes.GETSTATIC, "", "", "J")));
    assertEquals(
        -1, ByteCodeHelper.adjustStackUsage(new FieldInsnNode(Opcodes.PUTSTATIC, "", "", "I")));
    assertEquals(
        -2, ByteCodeHelper.adjustStackUsage(new FieldInsnNode(Opcodes.PUTSTATIC, "", "", "J")));
  }

  @Test
  public void methodInsn() {
    assertEquals(
        1,
        ByteCodeHelper.adjustStackUsage(new MethodInsnNode(Opcodes.INVOKESTATIC, "", "", "()I")));
    assertEquals(
        2,
        ByteCodeHelper.adjustStackUsage(new MethodInsnNode(Opcodes.INVOKESTATIC, "", "", "()J")));
    assertEquals(
        -3,
        ByteCodeHelper.adjustStackUsage(new MethodInsnNode(Opcodes.INVOKESTATIC, "", "", "(JJ)I")));
    assertEquals(
        -4,
        ByteCodeHelper.adjustStackUsage(
            new MethodInsnNode(Opcodes.INVOKESPECIAL, "", "", "(JJ)I")));
    assertEquals(
        -4,
        ByteCodeHelper.adjustStackUsage(
            new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "", "", "(JJ)I")));
    assertEquals(
        -4,
        ByteCodeHelper.adjustStackUsage(
            new MethodInsnNode(Opcodes.INVOKEINTERFACE, "", "", "(JJ)I")));
  }

  @Test
  public void invokeDynamicInsn() {
    assertEquals(
        -1, ByteCodeHelper.adjustStackUsage(new InvokeDynamicInsnNode("apply", "(J)I", null)));
  }

  @Test
  public void jumpInsn() {
    assertEquals(-1, ByteCodeHelper.adjustStackUsage(new JumpInsnNode(Opcodes.IFEQ, null)));
    assertEquals(-2, ByteCodeHelper.adjustStackUsage(new JumpInsnNode(Opcodes.IF_ICMPEQ, null)));
    assertEquals(1, ByteCodeHelper.adjustStackUsage(new JumpInsnNode(Opcodes.JSR, null)));
    assertEquals(0, ByteCodeHelper.adjustStackUsage(new JumpInsnNode(Opcodes.GOTO, null)));
  }

  @Test
  public void ldcInsn() {
    assertEquals(1, ByteCodeHelper.adjustStackUsage(new LdcInsnNode(1)));
    assertEquals(2, ByteCodeHelper.adjustStackUsage(new LdcInsnNode(1L)));
  }

  @Test
  public void iincInsn() {
    assertEquals(0, ByteCodeHelper.adjustStackUsage(new IincInsnNode(1, 1)));
  }

  @Test
  public void tableSwitchInsn() {
    assertEquals(-1, ByteCodeHelper.adjustStackUsage(new TableSwitchInsnNode(1, 1, null)));
  }

  @Test
  public void lookupSwitchInsn() {
    assertEquals(-1, ByteCodeHelper.adjustStackUsage(new LookupSwitchInsnNode(null, null, null)));
  }

  @Test
  public void multiANewArrayInsn() {
    assertEquals(-2, ByteCodeHelper.adjustStackUsage(new MultiANewArrayInsnNode("", 3)));
  }
}
