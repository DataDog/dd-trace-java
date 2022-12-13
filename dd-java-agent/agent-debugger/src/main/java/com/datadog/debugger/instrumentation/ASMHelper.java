package com.datadog.debugger.instrumentation;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class ASMHelper {
  public static void invokeStatic(
      InsnList insnList, Type owner, String name, Type returnType, Type... argTypes) {
    // expected stack: [arg_type_1 ... arg_type_N]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            owner.getInternalName(),
            name,
            Type.getMethodDescriptor(returnType, argTypes),
            false)); // stack: [ret_type]
  }

  public static void invokeVirtual(
      InsnList insnList, Type owner, String name, Type returnType, Type... argTypes) {
    // expected stack: [this, arg_type_1 ... arg_type_N]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            owner.getInternalName(),
            name,
            Type.getMethodDescriptor(returnType, argTypes),
            false)); // stack: [ret_type]
  }

  public static void invokeConstructor(InsnList insnList, Type owner, Type... argTypes) {
    // expected stack: [instance, arg_type_1 ... arg_type_N]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            owner.getInternalName(),
            Types.CONSTRUCTOR,
            Type.getMethodDescriptor(Type.VOID_TYPE, argTypes),
            false)); // stack: []
  }

  public static void newInstance(InsnList insnList, Type type) {
    insnList.add(new TypeInsnNode(Opcodes.NEW, type.getInternalName()));
  }

  public static void ldc(InsnList insnList, int val) {
    insnList.add(new LdcInsnNode(val));
  }

  public static void ldc(InsnList insnList, long val) {
    insnList.add(new LdcInsnNode(val));
  }

  public static void ldc(InsnList insnList, Object val) {
    insnList.add(val == null ? new InsnNode(Opcodes.ACONST_NULL) : new LdcInsnNode(val));
  }

  public static void storeVarDefaultValue(InsnList insnList, Type type, int varIndex) {
    switch (type.getSort()) {
      case Type.OBJECT:
        insnList.add(new InsnNode(Opcodes.ACONST_NULL));
        break;
      case Type.BYTE:
      case Type.SHORT:
      case Type.CHAR:
      case Type.INT:
        insnList.add(new InsnNode(Opcodes.ICONST_0));
        break;
      case Type.LONG:
        insnList.add(new InsnNode(Opcodes.LCONST_0));
        break;
      case Type.FLOAT:
        insnList.add(new InsnNode(Opcodes.FCONST_0));
        break;
      case Type.DOUBLE:
        insnList.add(new InsnNode(Opcodes.DCONST_0));
        break;
    }
    insnList.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), varIndex));
  }
}
