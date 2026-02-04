package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.Types.REFLECTIVE_FIELD_VALUE_RESOLVER_TYPE;
import static java.lang.String.format;
import static org.objectweb.asm.Type.getMethodDescriptor;
import static org.objectweb.asm.Type.getObjectType;

import com.datadog.debugger.agent.Generated;
import datadog.trace.util.Strings;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

/** Helper class for bytecode generation */
public class ASMHelper {
  public static final Type INT_TYPE = new Type(org.objectweb.asm.Type.INT_TYPE);
  public static final Type OBJECT_TYPE = new Type(Types.OBJECT_TYPE);
  public static final Type STRING_TYPE = new Type(Types.STRING_TYPE);
  public static final Type LONG_TYPE = new Type(org.objectweb.asm.Type.LONG_TYPE);

  public static void dumpMethod(ClassNode classNode, String method, String suffix) {
    String content = extractMethod(classNode, method);
    File output;
    int count = 0;
    do {
      output = new File(format("build/%s-%d-%s.txt", method, count++, suffix));
    } while (output.exists());
    output.getParentFile().mkdirs();
    try (PrintWriter writer = new PrintWriter(output)) {
      writer.println(content);
      String absolutePath = output.getAbsolutePath();
      absolutePath = absolutePath.substring(0, absolutePath.lastIndexOf('.'));
      absolutePath += ".class";
      ClassWriter classWriter = new ClassWriter(0);
      classNode.accept(classWriter);
      try (FileOutputStream stream = new FileOutputStream(absolutePath)) {
        stream.write(classWriter.toByteArray());
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressForbidden
  static String extractMethod(ClassNode classNode, String method) {
    StringJoiner joiner = new StringJoiner("\n");
    joiner.add("Class: " + classNode.name);
    StringWriter writer = new StringWriter();
    classNode.accept(new TraceClassVisitor(null, new Textifier(), new PrintWriter(writer)));
    List<String> strings = Arrays.asList(writer.toString().split("\n"));
    for (int i = 0; i < strings.size(); i++) {
      if (strings.get(i).matches(format(".*(private|public).* %s\\(.*", method))) {
        while (!strings.get(i).equals(""))
          joiner.add(String.format("[%3d] %s", i, strings.get(i++)));
      }
    }
    return joiner.toString();
  }

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

  public static boolean isStaticField(Field field) {
    return Modifier.isStatic(field.getModifiers());
  }

  public static boolean isFinalField(FieldNode fieldNode) {
    return (fieldNode.access & Opcodes.ACC_FINAL) != 0;
  }

  public static boolean isFinalField(Field field) {
    return Modifier.isFinal(field.getModifiers());
  }

  public static boolean isRecord(ClassNode classNode) {
    return (classNode.access & Opcodes.ACC_RECORD) > 0;
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

  public static void getStatic(InsnList insnList, org.objectweb.asm.Type owner, String fieldName) {
    insnList.add(
        new FieldInsnNode(
            Opcodes.GETSTATIC, owner.getInternalName(), fieldName, owner.getDescriptor()));
  }

  public static void getStatic(
      InsnList insnList,
      org.objectweb.asm.Type owner,
      String fieldName,
      org.objectweb.asm.Type fieldType) {
    insnList.add(
        new FieldInsnNode(
            Opcodes.GETSTATIC, owner.getInternalName(), fieldName, fieldType.getDescriptor()));
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

  /**
   * Makes sure that the class we want to load is not the one that we are currently transforming
   * otherwise it will lead into a LinkageError
   *
   * @param className class name to load in '.' format (com.foo.bar.Class$InnerClass)
   * @param currentClassTransformed in '.' format (com.foo.bar.Class$InnerClass)
   * @param classLoader use for loading the class
   * @return the loaded class
   */
  public static Class<?> ensureSafeClassLoad(
      String className, String currentClassTransformed, ClassLoader classLoader) {
    if (currentClassTransformed == null) {
      // This is required to make sure we are not loading the class being transformed during
      // transformation as it will generate a LinkageError with
      // "attempted duplicate class definition"
      throw new IllegalArgumentException(
          "Cannot ensure loading class: "
              + className
              + " safely as current class being transformed is not provided (null)");
    }
    if (className.equals(currentClassTransformed)) {
      throw new IllegalArgumentException(
          "Cannot load class " + className + " as this is the class being currently transformed");
    }
    try {
      return Class.forName(className, true, classLoader);
    } catch (Throwable t) {
      throw new RuntimeException("Cannot load class " + className, t);
    }
  }

  public static void emitReflectiveCall(
      InsnList insnList, Type fieldType, org.objectweb.asm.Type targetType) {
    int sort = fieldType.getMainType().getSort();
    String methodName = getReflectiveMethodName(sort);
    // stack: [target_object, string]
    org.objectweb.asm.Type returnType =
        sort == org.objectweb.asm.Type.OBJECT || sort == org.objectweb.asm.Type.ARRAY
            ? Types.OBJECT_TYPE
            : fieldType.getMainType();
    invokeStatic(
        insnList,
        REFLECTIVE_FIELD_VALUE_RESOLVER_TYPE,
        methodName,
        returnType,
        targetType,
        Types.STRING_TYPE);
    // stack: [field_value]
  }

  private static String getReflectiveMethodName(int sort) {
    switch (sort) {
      case org.objectweb.asm.Type.LONG:
        return "getFieldValueAsLong";
      case org.objectweb.asm.Type.INT:
        return "getFieldValueAsInt";
      case org.objectweb.asm.Type.DOUBLE:
        return "getFieldValueAsDouble";
      case org.objectweb.asm.Type.FLOAT:
        return "getFieldValueAsFloat";
      case org.objectweb.asm.Type.SHORT:
        return "getFieldValueAsShort";
      case org.objectweb.asm.Type.CHAR:
        return "getFieldValueAsChar";
      case org.objectweb.asm.Type.BYTE:
        return "getFieldValueAsByte";
      case org.objectweb.asm.Type.BOOLEAN:
        return "getFieldValueAsBoolean";
      case org.objectweb.asm.Type.OBJECT:
      case org.objectweb.asm.Type.ARRAY:
        return "getFieldValue";
      default:
        throw new IllegalArgumentException("Unsupported type sort:" + sort);
    }
  }

  public static List<LocalVariableNode> sortLocalVariables(List<LocalVariableNode> localVariables) {
    List<LocalVariableNode> sortedLocalVars = new ArrayList<>(localVariables);
    sortedLocalVars.sort(Comparator.comparingInt(o -> o.index));
    return sortedLocalVars;
  }

  public static LocalVariableNode[] createLocalVarNodes(List<LocalVariableNode> sortedLocalVars) {
    LocalVariableNode maxVarNode = sortedLocalVars.get(sortedLocalVars.size() - 1);
    int maxIndex = maxVarNode.index;
    org.objectweb.asm.Type localType = org.objectweb.asm.Type.getType(maxVarNode.desc);
    LocalVariableNode[] localVars = new LocalVariableNode[maxIndex + localType.getSize()];
    for (LocalVariableNode localVariableNode : sortedLocalVars) {
      localVars[localVariableNode.index] = localVariableNode;
    }
    return localVars;
  }

  public static void adjustLocalVarsBasedOnArgs(
      boolean isStatic,
      LocalVariableNode[] localVars,
      org.objectweb.asm.Type[] argTypes,
      List<LocalVariableNode> sortedLocalVars) {
    // assume that first local variables matches method arguments
    // as stated into the JVM spec:
    // https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-2.html#jvms-2.6.1
    // so we reassigned local var in arg slots if they are empty
    if (argTypes.length < localVars.length) {
      List<LocalVariableNode> uniqueSortedLocalVars = dedupLocalVars(sortedLocalVars);
      int slot = isStatic ? 0 : 1;
      int localVarTableIdx = slot;
      for (org.objectweb.asm.Type t : argTypes) {
        if (slot >= localVars.length) {
          break;
        }
        if (localVars[slot] == null && localVarTableIdx < uniqueSortedLocalVars.size()) {
          localVars[slot] = uniqueSortedLocalVars.get(localVarTableIdx);
        }
        slot += t.getSize();
        localVarTableIdx++;
      }
    }
  }

  private static List<LocalVariableNode> dedupLocalVars(List<LocalVariableNode> sortedLocalVars) {
    List<LocalVariableNode> uniqueSortedLocalVars = new ArrayList<>();
    int maxIndex = sortedLocalVars.get(sortedLocalVars.size() - 1).index;
    boolean[] usedIndexes = new boolean[maxIndex + 1];
    for (LocalVariableNode localVariableNode : sortedLocalVars) {
      if (!usedIndexes[localVariableNode.index]) {
        uniqueSortedLocalVars.add(localVariableNode);
        usedIndexes[localVariableNode.index] = true;
      }
    }
    return uniqueSortedLocalVars;
  }

  public static void newInstance(InsnList insnList, org.objectweb.asm.Type type) {
    insnList.add(new TypeInsnNode(Opcodes.NEW, type.getInternalName()));
  }

  public static void invokeConstructor(
      InsnList insnList, org.objectweb.asm.Type owner, org.objectweb.asm.Type... argTypes) {
    // expected stack: [instance, arg_type_1 ... arg_type_N]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            owner.getInternalName(),
            Types.CONSTRUCTOR,
            org.objectweb.asm.Type.getMethodDescriptor(org.objectweb.asm.Type.VOID_TYPE, argTypes),
            false));
    // stack: []
  }

  /** Checks if the given variable is in scope at the given location */
  public static boolean isInScope(
      MethodNode methodNode, LocalVariableNode variableNode, AbstractInsnNode location) {
    AbstractInsnNode startScope =
        variableNode.start != null ? variableNode.start : methodNode.instructions.getFirst();
    AbstractInsnNode endScope =
        variableNode.end != null ? variableNode.end : methodNode.instructions.getLast();
    AbstractInsnNode insn = startScope;
    while (insn != null && insn != endScope) {
      if (insn == location) {
        return true;
      }
      insn = insn.getNext();
    }
    return false;
  }

  public static boolean isStoreCompatibleType(
      org.objectweb.asm.Type previousType, org.objectweb.asm.Type currentType) {
    if (previousType == null || currentType == null) {
      return false;
    }
    if (previousType.getSort() == currentType.getSort()) {
      return true;
    }
    int previousSort = widenIntType(previousType.getSort());
    int currentSort = widenIntType(currentType.getSort());
    return previousSort == currentSort;
  }

  public static String toString(AbstractInsnNode node) {
    String opcode = node.getOpcode() >= 0 ? Printer.OPCODES[node.getOpcode()] : node.toString();
    if (node instanceof LineNumberNode) {
      return String.format("LineNumber: %s", ((LineNumberNode) node).line);
    } else if (node instanceof MethodInsnNode) {
      MethodInsnNode method = (MethodInsnNode) node;
      return String.format("%s: [%s] %s", opcode, method.name, method.desc);
    } else {
      return opcode;
    }
  }

  private static int widenIntType(int sort) {
    switch (sort) {
      case org.objectweb.asm.Type.BOOLEAN:
      case org.objectweb.asm.Type.BYTE:
      case org.objectweb.asm.Type.CHAR:
      case org.objectweb.asm.Type.SHORT:
        return org.objectweb.asm.Type.INT;
    }
    return sort;
  }

  public static String extractSuperClass(ClassNode classNode) {
    if (classNode.superName == null) {
      return Object.class.getTypeName();
    }
    return Strings.getClassName(classNode.superName);
  }

  public static boolean isStore(int opcode) {
    return opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE;
  }

  public static List<Integer> getLineNumbers(MethodNode methodNode) {
    List<Integer> lines = new ArrayList<>();
    if (methodNode == null) {
      return lines;
    }
    AbstractInsnNode current = methodNode.instructions.getFirst();
    while (current != null) {
      if (current.getType() == AbstractInsnNode.LINE) {
        LineNumberNode lineNode = (LineNumberNode) current;
        lines.add(lineNode.line);
      }
      current = current.getNext();
    }
    return lines;
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
