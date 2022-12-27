package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;

import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Where;
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
import org.objectweb.asm.tree.TypeInsnNode;

/** Common class for generating instrumentation */
public class Instrumentor {
  protected static final String CONSTRUCTOR_NAME = "<init>";

  protected final ProbeDefinition definition;
  protected final ClassLoader classLoader;
  protected final ClassNode classNode;
  protected final MethodNode methodNode;
  protected final List<DiagnosticMessage> diagnostics;
  protected final boolean isStatic;
  protected final boolean isLineProbe;
  protected final LineMap lineMap = new LineMap();
  protected final LabelNode methodEnterLabel;
  protected int localVarBaseOffset;
  protected int argOffset;
  protected final String[] argumentNames;
  protected LabelNode returnHandlerLabel;

  public Instrumentor(
      ProbeDefinition definition,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics) {
    this.definition = definition;
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

  protected void processInstructions() {
    AbstractInsnNode node = methodNode.instructions.getFirst();
    while (node != null && !node.equals(returnHandlerLabel)) {
      if (node.getType() == AbstractInsnNode.LINE) {
        lineMap.addLine((LineNumberNode) node);
      } else {
        node = processInstruction(node);
      }
      node = node.getNext();
    }
    if (returnHandlerLabel == null) {
      // if no return found, fallback to use the last instruction as last resort
      returnHandlerLabel = new LabelNode();
      methodNode.instructions.insert(methodNode.instructions.getLast(), returnHandlerLabel);
    }
  }

  protected AbstractInsnNode processInstruction(AbstractInsnNode node) {
    switch (node.getOpcode()) {
      case Opcodes.RET:
      case Opcodes.RETURN:
      case Opcodes.IRETURN:
      case Opcodes.FRETURN:
      case Opcodes.LRETURN:
      case Opcodes.DRETURN:
      case Opcodes.ARETURN:
        {
          InsnList beforeReturnInsnList = getBeforeReturnInsnList(node);
          if (beforeReturnInsnList != null) {
            methodNode.instructions.insertBefore(node, beforeReturnInsnList);
          }
          AbstractInsnNode prev = node.getPrevious();
          methodNode.instructions.remove(node);
          methodNode.instructions.insert(
              prev, new JumpInsnNode(Opcodes.GOTO, getReturnHandler(node)));
          return prev;
        }
    }
    return node;
  }

  protected InsnList getBeforeReturnInsnList(AbstractInsnNode node) {
    return null;
  }

  protected LabelNode getReturnHandler(AbstractInsnNode exitNode) {
    // exit node must have been removed from the original instruction list
    if (exitNode.getNext() != null || exitNode.getPrevious() != null) {
      throw new IllegalArgumentException("exitNode is not removed from original instruction list");
    }
    if (returnHandlerLabel != null) {
      return returnHandlerLabel;
    }
    returnHandlerLabel = new LabelNode();
    methodNode.instructions.add(returnHandlerLabel);
    // stack top is return value (if any)
    InsnList handler = getReturnHandlerInsnList();
    handler.add(exitNode); // stack: []
    methodNode.instructions.add(handler);
    return returnHandlerLabel;
  }

  protected InsnList getReturnHandlerInsnList() {
    return new InsnList();
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

  protected void pushTags(InsnList insnList, ProbeDefinition.Tag[] tags) {
    if (tags == null || tags.length == 0) {
      insnList.add(new InsnNode(Opcodes.ACONST_NULL));
      return;
    }
    ldc(insnList, tags.length); // stack: [int]
    insnList.add(
        new TypeInsnNode(Opcodes.ANEWARRAY, STRING_TYPE.getInternalName())); // stack: [array]
    int counter = 0;
    for (ProbeDefinition.Tag tag : tags) {
      insnList.add(new InsnNode(Opcodes.DUP)); // stack: [array, array]
      ldc(insnList, counter++); // stack: [array, array, int]
      ldc(insnList, tag.toString()); // stack: [array, array, int, string]
      insnList.add(new InsnNode(Opcodes.AASTORE)); // stack: [array]
    }
  }

  protected int newVar(Type type) {
    int varId = methodNode.maxLocals + (type.getSize());
    methodNode.maxLocals = varId;
    return varId;
  }

  protected void invokeVirtual(
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

  protected void invokeInterface(
      InsnList insnList, Type owner, String name, Type returnType, Type... argTypes) {
    // expected stack: [this, arg_type_1 ... arg_type_N]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKEINTERFACE,
            owner.getInternalName(),
            name,
            Type.getMethodDescriptor(returnType, argTypes),
            true)); // stack: [ret_type]
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
