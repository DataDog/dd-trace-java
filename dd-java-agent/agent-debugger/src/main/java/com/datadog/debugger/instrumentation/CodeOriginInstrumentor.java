package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;

import com.datadog.debugger.instrumentation.InstrumentationResult.Status;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;

public class CodeOriginInstrumentor extends Instrumentor {
  private static final String CAPTURE;

  static {
    String className = DebuggerContext.class.getName().replace('.', '/');
    CAPTURE = format("%s#%s", className, "captureCodeOrigin");
  }

  private static String buildDescription(AbstractInsnNode node) {
    if (!(node instanceof MethodInsnNode)) return "";
    MethodInsnNode method = (MethodInsnNode) node;
    return format("%s#%s", method.owner, method.name);
  }

  public CodeOriginInstrumentor(
      ProbeDefinition definition, MethodInfo methodInfo, List<ProbeId> probeIds) {
    super(definition, methodInfo, null, probeIds);
  }

  @Override
  public Status instrument() {
    AbstractInsnNode insertionPoint = stripSetup();
    methodNode.instructions.insert(
        insertionPoint != null ? insertionPoint : findInsertionPoint(), codeOriginCall());

    return Status.INSTALLED;
  }

  private InsnList codeOriginCall() {
    InsnList insnList = new InsnList();
    ldc(insnList, probeIds.get(0).getEncodedId());
    invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "codeOrigin", Type.VOID_TYPE, STRING_TYPE);
    return insnList;
  }

  private AbstractInsnNode stripSetup() {
    ListIterator<AbstractInsnNode> iterator = methodNode.instructions.iterator();
    List<AbstractInsnNode> list = new ArrayList<>();
    AbstractInsnNode insertionPoint = null;
    while (iterator.hasNext()) {
      AbstractInsnNode next = iterator.next();
      if (buildDescription(next).equals(CAPTURE)) {
        for (int i = 0; i < 10; i++) {
          list.remove(list.size() - 1);
        }
        insertionPoint = list.get(list.size() - 1);
      } else {
        list.add(next);
      }
    }
    methodNode.instructions = new InsnList();
    list.forEach(n -> methodNode.instructions.add(n));
    return insertionPoint;
  }

  private AbstractInsnNode findInsertionPoint() {
    CodeOriginProbe probe = (CodeOriginProbe) definition;
    List<String> lines = probe.getLocation().getLines();
    if (!probe.entrySpanProbe() && lines != null && !lines.isEmpty()) {
      LabelNode lineLabel = classFileLines.getLineLabel(parseInt(lines.get(0)));
      if (lineLabel != null) {
        return lineLabel.getNext();
      }
    }
    return methodEnterLabel;
  }
}
