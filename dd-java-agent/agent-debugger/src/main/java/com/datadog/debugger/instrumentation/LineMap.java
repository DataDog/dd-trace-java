package com.datadog.debugger.instrumentation;

import org.objectweb.asm.Label;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Custom [line number -> {@linkplain LabelNode}] map for easier determination of local variables
 * scopes
 */
public class LineMap {
  private final TreeMap<Integer, List<LabelNode>> lineLabels = new TreeMap<>();


  public void addLine(LineNumberNode lineNode) {
    lineLabels.merge(lineNode.line, new ArrayList<>(Collections.singletonList(lineNode.start)), (prev, next) -> {
      prev.addAll(next);
      return prev;
    });
  }

  public LabelNode getLineLabel(int line) {
    Map.Entry<Integer, List<LabelNode>> nextLine = lineLabels.ceilingEntry(line);
    if (nextLine == null) return null;
    List<LabelNode> labels = nextLine.getValue();
    return labels.get(labels.size() - 1);
  }

  public int getNextLine(int line) {
    return lineLabels.higherKey(line);
  }

  public int getLine(Label label) {
    int f = -1;
    for (Map.Entry<Integer, List<LabelNode>> entry : lineLabels.entrySet()) {
      for (LabelNode labelNode : entry.getValue()) {
        if (labelNode.getLabel().equals(label)) {
          f = Math.max(f, entry.getKey());
        }
      }
    }
    return f;
  }
//  public int getLine(Label label) {
//    Map.Entry<LineNumberLabel, Integer> nextLine = labelLines.ceilingEntry(new LineNumberLabel(null, label));
//    if (nextLine == null) return -1;
//    return nextLine.getKey().getLineNumberNode().line;
//  }

  public boolean isEmpty() {
    return lineLabels.isEmpty();
  }

  private static class LineNumberLabel implements Comparable<LineNumberLabel> {
    private final LineNumberNode lineNumberNode;
    private final Label label;

    private LineNumberLabel(LineNumberNode lineNumberNode, Label labelNode) {
      this.lineNumberNode = lineNumberNode;
      this.label = labelNode;
    }

    public LineNumberNode getLineNumberNode() {
      return lineNumberNode;
    }

    public Label getLabel() {
      return label;
    }

    @Override
    public int compareTo(LineNumberLabel o) {
      return getLineNumberNode().line - o.getLineNumberNode().line;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      LineNumberLabel that = (LineNumberLabel) o;

      return Objects.equals(label, that.label);
    }

    @Override
    public int hashCode() {
      return label != null ? label.hashCode() : 0;
    }

    @Override
    public String toString() {
      return "LineNumberLabel{" +
          "lineNumberNode=" + lineNumberNode +
          ", labelNode=" + label +
          '}';
    }
  }
}
