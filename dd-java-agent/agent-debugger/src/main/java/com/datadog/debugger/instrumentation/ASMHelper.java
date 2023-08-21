package com.datadog.debugger.instrumentation;

import static org.objectweb.asm.Type.getMethodDescriptor;
import static org.objectweb.asm.Type.getObjectType;

import com.datadog.debugger.agent.Generated;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Helper class for bytecode generation */
public class ASMHelper {
  public static final Type INT_TYPE = new Type(org.objectweb.asm.Type.INT_TYPE);
  public static final Type OBJECT_TYPE = new Type(Types.OBJECT_TYPE);
  public static final Type STRING_TYPE = new Type(Types.STRING_TYPE);
  public static final Type LONG_TYPE = new Type(org.objectweb.asm.Type.LONG_TYPE);

  public static void invokeInterface(
      InsnList insnList,
      org.objectweb.asm.Type owner,
      String name,
      org.objectweb.asm.Type returnType,
      org.objectweb.asm.Type... argTypes) {
    // expected stack: [this, arg_type_1 ... arg_type_N]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKEINTERFACE,
            owner.getInternalName(),
            name,
            getMethodDescriptor(returnType, argTypes),
            true));
    // stack: [ret_type]
  }

  public static void invokeVirtual(
      InsnList insnList,
      org.objectweb.asm.Type owner,
      String name,
      org.objectweb.asm.Type returnType,
      org.objectweb.asm.Type... argTypes) {
    // expected stack: [this, arg_type_1 ... arg_type_N]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            owner.getInternalName(),
            name,
            getMethodDescriptor(returnType, argTypes),
            false));
    // stack: [ret_type]
  }

  public static boolean isStaticField(FieldNode fieldNode) {
    return (fieldNode.access & Opcodes.ACC_STATIC) != 0;
  }

  public static void invokeStatic(
      InsnList insnList,
      org.objectweb.asm.Type owner,
      String name,
      org.objectweb.asm.Type returnType,
      org.objectweb.asm.Type... argTypes) {
    // expected stack: [arg_type_1 ... arg_type_N]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            owner.getInternalName(),
            name,
            getMethodDescriptor(returnType, argTypes),
            false)); // stack: [ret_type]
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

  public static Type decodeSignature(String signature) {
    SignatureReader sigReader = new SignatureReader(signature);
    FieldSignatureVisitor fieldSignatureVisitor = new FieldSignatureVisitor();
    sigReader.acceptType(fieldSignatureVisitor);
    org.objectweb.asm.Type mainType = getObjectType(fieldSignatureVisitor.getMainClassName());
    List<Type> genericTypes =
        fieldSignatureVisitor.genericTypes.stream()
            .map(org.objectweb.asm.Type::getObjectType)
            .map(Type::new)
            .collect(Collectors.toList());
    return new Type(mainType, genericTypes);
  }

  /** Wraps ASM's {@link org.objectweb.asm.Type} with associated generic types */
  public static class Type {
    private final org.objectweb.asm.Type mainType;
    private final List<Type> genericTypes;

    public Type(org.objectweb.asm.Type mainType) {
      this.mainType = mainType;
      this.genericTypes = Collections.emptyList();
    }

    public Type(org.objectweb.asm.Type mainType, List<Type> genericTypes) {
      this.mainType = mainType;
      this.genericTypes = genericTypes;
    }

    public org.objectweb.asm.Type getMainType() {
      return mainType;
    }

    public List<Type> getGenericTypes() {
      return genericTypes;
    }

    @Generated
    @Override
    public String toString() {
      return "Type{" + "mainType=" + mainType + ", genericTypes=" + genericTypes + '}';
    }
  }

  private static class FieldSignatureVisitor extends SignatureVisitor {
    private String mainClassName;
    private boolean genericType;
    private List<String> genericTypes = new ArrayList<>();

    public FieldSignatureVisitor() {
      super(Opcodes.ASM9);
    }

    @Override
    public SignatureVisitor visitTypeArgument(char wildcard) {
      genericType = true;
      return super.visitTypeArgument(wildcard);
    }

    @Override
    public void visitClassType(String name) {
      if (genericType) {
        genericTypes.add(name);
      } else {
        mainClassName = name;
      }
    }

    @Override
    public void visitEnd() {
      super.visitEnd();
    }

    public String getMainClassName() {
      return mainClassName;
    }

    public List<String> getGenericTypes() {
      return genericTypes;
    }
  }
}
