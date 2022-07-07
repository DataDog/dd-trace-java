package com.datadog.debugger.instrumentation;

import com.datadog.debugger.agent.ProbeDefinition;
import com.datadog.debugger.agent.Where;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.DiagnosticMessage.Kind;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Common class for generating instrumentation */
public class Instrumentor {
  protected static final String CONSTRUCTOR_NAME = "<init>";

  protected final ClassLoader classLoader;
  protected final ClassNode classNode;
  protected final MethodNode methodNode;
  protected final List<DiagnosticMessage> diagnostics;
  protected final boolean isStatic;
  protected final boolean isLineProbe;
  protected final LineMap lineMap = new LineMap();
  protected final LabelNode methodEnterLabel;
  protected int localVarBaseOffset;
  protected int argOffset = 0;
  protected final String[] argumentNames;

  public Instrumentor(
      ProbeDefinition definition,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics) {
    this.classLoader = classLoader;
    this.classNode = classNode;
    this.methodNode = methodNode;
    this.diagnostics = diagnostics;
    Where.SourceLine[] sourceLines = definition.getWhere().getSourceLines();
    isLineProbe = sourceLines != null && sourceLines.length > 0;
    isStatic = (methodNode.access & Opcodes.ACC_STATIC) != 0;
    methodEnterLabel = insertMethodEnterLabel();
    argOffset = isStatic ? 0 : 1;
    Type[] argTypes = Type.getArgumentTypes(methodNode.desc);
    for (Type t : argTypes) {
      argOffset += t.getSize();
    }
    argumentNames = extractArgumentNames(argTypes);
  }

  private String[] extractArgumentNames(Type[] argTypes) {
    String[] argumentNames = new String[argOffset];
    if (methodNode.localVariables != null && !methodNode.localVariables.isEmpty()) {
      localVarBaseOffset =
          methodNode.localVariables.stream().mapToInt(v -> v.index).min().orElse(0);
      for (LocalVariableNode localVariableNode : methodNode.localVariables) {
        int idx = localVariableNode.index - localVarBaseOffset;
        if (idx < argOffset) {
          argumentNames[idx] = localVariableNode.name;
        }
      }
    } else {
      int slot = isStatic ? 0 : 1;
      int index = 0;
      for (Type t : argTypes) {
        argumentNames[slot] = "p" + (index++);
        slot += t.getSize();
      }
    }
    return argumentNames;
  }

  private LabelNode insertMethodEnterLabel() {
    // insert a label just at the beginning of the method
    // (but after invoke to super() or this() for constructor)
    // used to anchor the insertion of declaration of snapshot var
    LabelNode methodEnterLabel = new LabelNode();
    if (methodNode.name.equals(CONSTRUCTOR_NAME)) {
      AbstractInsnNode first = methodNode.instructions.getFirst();
      first = findFirstInsnForConstructor(first);
      methodNode.instructions.insert(first, methodEnterLabel);
    } else {
      methodNode.instructions.insert(methodEnterLabel);
    }
    return methodEnterLabel;
  }

  private AbstractInsnNode findFirstInsnForConstructor(AbstractInsnNode first) {
    // Skip call to super() or this() for constructors
    AbstractInsnNode lastInvokeSpecial = first;
    int stackCount = 0;
    while (first != null) {
      stackCount += ByteCodeHelper.adjustStackUsage(first);
      if (first.getType() == AbstractInsnNode.JUMP_INSN) {
        // always jump to a destination
        first = ((JumpInsnNode) first).label;
        continue;
      }
      if (stackCount == 0 && first.getOpcode() == Opcodes.INVOKESPECIAL) {
        MethodInsnNode methodInsnNode = (MethodInsnNode) first;
        if (methodInsnNode.owner.equals(classNode.superName)) { // super() case
          return first;
        }
        if (methodInsnNode.owner.equals(classNode.name)
            && (!methodInsnNode.desc.equals(methodNode.desc))) {
          // first call to another constructor of the same class => this(...) case
          return first;
        }
      }
      first = first.getNext();
    }
    return lastInvokeSpecial;
  }

  protected void fillLineMap() {
    AbstractInsnNode node = methodNode.instructions.getFirst();
    while (node != null) {
      if (node.getType() == AbstractInsnNode.LINE) {
        lineMap.addLine((LineNumberNode) node);
      }
      node = node.getNext();
    }
  }

  protected static void invokeStatic(
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

  protected static void ldc(InsnList insnList, int val) {
    insnList.add(new LdcInsnNode(val));
  }

  protected static void ldc(InsnList insnList, long val) {
    insnList.add(new LdcInsnNode(val));
  }

  protected static void ldc(InsnList insnList, Object val) {
    insnList.add(val == null ? new InsnNode(Opcodes.ACONST_NULL) : new LdcInsnNode(val));
  }

  protected static boolean isStaticField(FieldNode fieldNode) {
    return (fieldNode.access & Opcodes.ACC_STATIC) != 0;
  }

  protected void reportError(String message) {
    diagnostics.add(new DiagnosticMessage(Kind.ERROR, message));
  }

  protected void reportWarning(String message) {
    diagnostics.add(new DiagnosticMessage(Kind.WARN, message));
  }
}
