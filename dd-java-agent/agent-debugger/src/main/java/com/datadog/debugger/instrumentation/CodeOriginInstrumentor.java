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
import java.util.List;
import java.util.ListIterator;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;

public class CodeOriginInstrumentor extends Instrumentor {
  private static final String MARKER;
  private static final String CAPTURE;

  static {
    String className = DebuggerContext.class.getName().replace('.', '/');
    MARKER = format("%s#%s", className, "marker");
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
    InsnList insnList = new InsnList();

    ldc(insnList, probeIds.get(0).getEncodedId());
    invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "codeOrigin", Type.VOID_TYPE, STRING_TYPE);
    methodNode.instructions.insert(findInsertionPoint(), insnList);

    stripSetup();
    return Status.INSTALLED;
  }

  private void stripSetup() {
    ListIterator<AbstractInsnNode> iterator = methodNode.instructions.iterator();
    InsnList list = new InsnList();
    while (iterator.hasNext()) {
      AbstractInsnNode next = iterator.next();
      if (buildDescription(next).equals(MARKER)) {
        while (!buildDescription(next).equals(CAPTURE)) {
          next = iterator.next();
        }
        next = iterator.next();
      }
      list.add(next);
    }
    methodNode.instructions = list;
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
