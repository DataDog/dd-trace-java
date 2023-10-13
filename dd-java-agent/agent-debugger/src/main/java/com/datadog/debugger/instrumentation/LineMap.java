package com.datadog.debugger.instrumentation;

import java.util.Map;
import java.util.TreeMap;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;

/**
 * Custom [line number -> {@linkplain LabelNode}] map for easier determination of local variables
 * scopes
 */
class LineMap {
  private final TreeMap<Integer, LabelNode> lineLabels = new TreeMap<>();

  void addLine(LineNumberNode lineNode) {
    // we are putting the line only the first time in the map.
    // for-loops can generate 2 line nodes for the same line, 1 before the loop, 1 for the
    // incrementation part.
    // We consider here that we want to place the probe before the loop.
    lineLabels.putIfAbsent(lineNode.line, lineNode.start);
  }

  LabelNode getLineLabel(int line) {
    Map.Entry<Integer, LabelNode> nextLine = lineLabels.ceilingEntry(line);
    if (nextLine == null) return null;
    return nextLine.getValue();
  }

  boolean isEmpty() {
    return lineLabels.isEmpty();
  }
}
