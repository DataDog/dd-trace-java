package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.adjustLocalVarsBasedOnArgs;
import static com.datadog.debugger.instrumentation.ASMHelper.createLocalVarNodes;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.ASMHelper.sortLocalVariables;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;

import com.datadog.debugger.instrumentation.DiagnosticMessage.Kind;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.util.ClassFileLines;
import com.datadog.debugger.util.JvmLanguage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Common class for generating instrumentation */
public abstract class Instrumenter {
  protected static final String CONSTRUCTOR_NAME = "<init>";
  protected static final String PROBEID_TAG_NAME = "debugger.probeid";

  protected final ProbeDefinition definition;
  protected final ClassLoader classLoader;
  protected final ClassNode classNode;
  protected final MethodNode methodNode;
  protected final ClassFileLines classFileLines;
  protected final List<DiagnosticMessage> diagnostics;
  protected final List<Integer> probeIndices;
  protected final boolean isStatic;
  protected final LabelNode methodEnterLabel;
  protected int localVarBaseOffset;
  protected int argOffset;
  protected final LocalVariableNode[] localVarsBySlotArray;
  protected final JvmLanguage language;
  protected LabelNode returnHandlerLabel;
  protected final List<CapturedContextInstrumenter.FinallyBlock> finallyBlocks = new ArrayList<>();

  public Instrumenter(
      ProbeDefinition definition,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<Integer> probeIndices) {
    this.definition = definition;
    this.classLoader = methodInfo.getClassLoader();
    this.classNode = methodInfo.getClassNode();
    this.methodNode = methodInfo.getMethodNode();
    this.classFileLines = methodInfo.getClassFileLines();
    this.diagnostics = diagnostics;
    this.probeIndices = probeIndices;
    isStatic = (methodNode.access & Opcodes.ACC_STATIC) != 0;
    methodEnterLabel = insertMethodEnterLabel();
    argOffset = isStatic ? 0 : 1;
    Type[] argTypes = Type.getArgumentTypes(methodNode.desc);
    for (Type t : argTypes) {
      argOffset += t.getSize();
    }
    localVarsBySlotArray = extractLocalVariables(argTypes);
    this.language = JvmLanguage.of(classNode.sourceFile);
  }

  public abstract InstrumentationResult.Status instrument();

  private LocalVariableNode[] extractLocalVariables(Type[] argTypes) {
    if (methodNode.localVariables == null || methodNode.localVariables.isEmpty()) {
      return new LocalVariableNode[0];
    }
    List<LocalVariableNode> sortedLocalVars = sortLocalVariables(methodNode.localVariables);
    LocalVariableNode[] localVars = createLocalVarNodes(sortedLocalVars);
    adjustLocalVarsBasedOnArgs(isStatic, localVars, argTypes, sortedLocalVars);
    localVarBaseOffset = sortedLocalVars.get(0).index;
    return localVars;
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

  protected void processInstructions() {
    AbstractInsnNode node = methodNode.instructions.getFirst();
    LabelNode sentinelNode = new LabelNode();
    methodNode.instructions.add(sentinelNode);
    while (node != null && !node.equals(sentinelNode)) {
      if (node.getType() != AbstractInsnNode.LINE) {
        node = processInstruction(node);
      }
      node = node.getNext();
    }
    methodNode.instructions.remove(sentinelNode);
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
          // stack [ret_value]
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
    // Create the returnHandlerLabel every time because the stack state could be different
    // for each return (suspend method in Kotlin)
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
    int varId = methodNode.maxLocals + 1;
    methodNode.maxLocals += type.getSize();
    return varId;
  }

  protected int newVar(int size) {
    int varId = methodNode.maxLocals + 1;
    methodNode.maxLocals += size;
    return varId;
  }

  protected void reportError(String message) {
    diagnostics.add(new DiagnosticMessage(Kind.ERROR, message));
  }

  protected void reportWarning(String message) {
    diagnostics.add(new DiagnosticMessage(Kind.WARN, message));
  }

  protected ProbeDefinition.Tag[] addProbeIdWithTags(String probeId, ProbeDefinition.Tag[] tags) {
    if (tags == null) {
      return new ProbeDefinition.Tag[] {new ProbeDefinition.Tag(PROBEID_TAG_NAME, probeId)};
    }
    ProbeDefinition.Tag[] newTags = Arrays.copyOf(tags, tags.length + 1);
    newTags[newTags.length - 1] = new ProbeDefinition.Tag(PROBEID_TAG_NAME, probeId);
    return newTags;
  }

  protected InsnList clone(InsnList insnList) {
    InsnList result = new InsnList();
    Map<LabelNode, LabelNode> labels = new HashMap<>();
    for (AbstractInsnNode node : insnList) {
      if (node instanceof LabelNode) {
        labels.put((LabelNode) node, new LabelNode());
      }
    }
    for (AbstractInsnNode node : insnList) {
      result.add(node.clone(labels));
    }
    return result;
  }

  protected void installFinallyBlocks() {
    for (FinallyBlock finallyBlock : finallyBlocks) {
      methodNode.tryCatchBlocks.add(
          new TryCatchBlockNode(
              finallyBlock.startLabel, finallyBlock.endLabel, finallyBlock.handlerLabel, null));
    }
  }

  protected static class FinallyBlock {
    final LabelNode startLabel;
    final LabelNode endLabel;
    final LabelNode handlerLabel;

    public FinallyBlock(LabelNode startLabel, LabelNode endLabel, LabelNode handlerLabel) {
      this.startLabel = startLabel;
      this.endLabel = endLabel;
      this.handlerLabel = handlerLabel;
    }
  }
}
