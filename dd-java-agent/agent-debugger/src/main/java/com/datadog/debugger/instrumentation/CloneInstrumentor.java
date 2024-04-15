package com.datadog.debugger.instrumentation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public class CloneInstrumentor {

  private final ClassLoader classLoader;
  private final ClassNode classNode;
  private final MethodNode methodNode;

  public CloneInstrumentor(ClassLoader classLoader, ClassNode classNode, MethodNode methodNode) {
    this.classLoader = classLoader;
    this.classNode = classNode;
    this.methodNode = methodNode;
  }

  public void instrument() {
    MethodNode rewrittenMethod = copy(methodNode);
    rewrittenMethod.name = rewrittenMethod.name + "_rewritten";
    rewriteCall("process", "alternateProcess", rewrittenMethod);
    classNode.methods.add(rewrittenMethod);
    MethodNode originalMethod = copy(methodNode);
    originalMethod.name = originalMethod.name + "_original";
    classNode.methods.add(originalMethod);
    // rewrite original method
    methodNode.instructions.clear();
    Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
    boolean isStatic = (methodNode.access & Opcodes.ACC_STATIC) != 0;
    int argOffset = isStatic ? 0 : 1;
    for (Type argType : argumentTypes) {
      methodNode.instructions.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), argOffset++));
    }
    methodNode.instructions.add(
        new MethodInsnNode(
            Opcodes.INVOKESTATIC, classNode.name, rewrittenMethod.name, rewrittenMethod.desc));
    Type returnType = Type.getReturnType(methodNode.desc);
    methodNode.instructions.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
  }

  private void rewriteCall(String methodName, String newMethodName, MethodNode methodNode) {
    AbstractInsnNode node = methodNode.instructions.getFirst();
    while (node != null) {
      if (node instanceof MethodInsnNode) {
        MethodInsnNode mnode = (MethodInsnNode) node;
        if (mnode.name.equals(methodName)) {
          mnode.name = newMethodName;
        }
      }
      node = node.getNext();
    }
  }

  private MethodNode copy(MethodNode input) {
    MethodNode result = new MethodNode();
    result.desc = input.desc;
    result.access = input.access;
    result.localVariables = copyList(input.localVariables);
    result.instructions = new InsnList();
    Map<LabelNode, LabelNode> labels = new HashMap<>();
    AbstractInsnNode node = input.instructions.getFirst();
    while (node != null) {
      if (node instanceof LabelNode) {
        labels.put((LabelNode) node, new LabelNode());
      }
      node = node.getNext();
    }
    node = input.instructions.getFirst();
    while (node != null) {
      result.instructions.add(node.clone(labels));
      node = node.getNext();
    }
    result.parameters = input.parameters;
    result.tryCatchBlocks = copyList(input.tryCatchBlocks);
    result.annotationDefault = input.annotationDefault;
    result.attrs = copyList(input.attrs);
    result.exceptions = copyList(input.exceptions);
    result.invisibleAnnotableParameterCount = input.invisibleAnnotableParameterCount;
    result.invisibleAnnotations = copyList(input.invisibleAnnotations);
    result.invisibleLocalVariableAnnotations = copyList(input.invisibleLocalVariableAnnotations);
    result.invisibleParameterAnnotations = input.invisibleParameterAnnotations;
    result.invisibleTypeAnnotations = copyList(input.invisibleTypeAnnotations);
    result.maxLocals = input.maxLocals;
    result.name = input.name;
    result.maxStack = input.maxStack;
    result.signature = input.signature;
    result.visibleAnnotableParameterCount = input.visibleAnnotableParameterCount;
    result.visibleAnnotations = copyList(input.visibleAnnotations);
    result.visibleLocalVariableAnnotations = copyList(input.visibleLocalVariableAnnotations);
    result.visibleParameterAnnotations = input.visibleParameterAnnotations;
    result.visibleTypeAnnotations = copyList(input.visibleTypeAnnotations);
    return result;
  }

  private static <T> List<T> copyList(List<T> list) {
    if (list == null) {
      return null;
    }
    return new ArrayList<>(list);
  }
}
