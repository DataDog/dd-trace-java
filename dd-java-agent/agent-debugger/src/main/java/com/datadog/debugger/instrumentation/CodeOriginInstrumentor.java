package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;

import com.datadog.debugger.instrumentation.InstrumentationResult.Status;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import java.util.List;
import java.util.ListIterator;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeOriginInstrumentor extends Instrumentor {
  private static final String CAPTURE;
  private static final String MARKER;
  private static final Logger LOGGER = LoggerFactory.getLogger(CodeOriginInstrumentor.class);

  static {
    String className = DebuggerContext.class.getName().replace('.', '/');
    CAPTURE = format("%s#%s", className, "captureCodeOrigin");
    MARKER = format("%s#%s", className, "marker");
  }

  public CodeOriginInstrumentor(
      ProbeDefinition definition, MethodInfo methodInfo, List<Integer> probeIndices) {
    super(definition, methodInfo, null, probeIndices);
  }

  @Override
  public Status instrument() {
    AbstractInsnNode insertionPoint = stripSetup();
    methodNode.instructions.insert(
        insertionPoint != null ? insertionPoint : findInsertionPoint(), codeOriginCall());

    return Status.INSTALLED;
  }

  private static String buildDescription(AbstractInsnNode node) {
    if (!(node instanceof MethodInsnNode)) return "";
    MethodInsnNode method = (MethodInsnNode) node;
    return format("%s#%s", method.owner, method.name);
  }

  private InsnList codeOriginCall() {
    InsnList insnList = new InsnList();
    ldc(insnList, probeIndices.get(0));
    invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "codeOrigin", Type.VOID_TYPE, Type.INT_TYPE);
    return insnList;
  }

  private AbstractInsnNode stripSetup() {
    try {
      ListIterator<AbstractInsnNode> iterator = methodNode.instructions.iterator();
      AbstractInsnNode insertionPoint = null;
      InsnList list = new InsnList();
      while (iterator.hasNext()) {
        AbstractInsnNode next = iterator.next();
        if (buildDescription(next).equals(MARKER)) {
          insertionPoint = next.getPrevious();
          while (iterator.hasNext() && !buildDescription(next = iterator.next()).equals(CAPTURE)) {
            if (next instanceof LineNumberNode || next instanceof LabelNode) {
              list.add(next);
            }
          }

          if (!iterator.hasNext()) {
            return null;
          }
        } else {
          list.add(next);
        }
      }
      methodNode.instructions = list;
      return insertionPoint;
    } catch (Exception e) {
      LOGGER.debug("Error in captureCodeOrigin: ", e);
      return null;
    }
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
