package com.datadog.debugger.instrumentation;

import com.datadog.debugger.instrumentation.InstrumentationResult.Status;
import com.datadog.debugger.probe.OriginProbe;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

public class SpanOriginInstrumentor extends Instrumentor {
  public SpanOriginInstrumentor(
      OriginProbe originProbe,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<ProbeId> probeIds) {
    super(originProbe, methodInfo, diagnostics, probeIds);
  }

  @Override
  public Status instrument() {
    System.out.println("methodNode.signature = " + methodNode.signature);
    MethodNode node =
        new MethodNode(
            methodNode.access | Opcodes.ACC_SYNTHETIC,
            methodNode.name + "_copy",
            methodNode.desc,
            methodNode.signature,
            methodNode.exceptions.toArray(new String[0]));
    methodNode.instructions.accept(node);
    classNode.methods.add(node);
    return Status.INSTALLED;
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
    return list == null ? null : new ArrayList<>(list);
  }
}
