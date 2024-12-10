package com.datadog.debugger.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

public class ClassFileLines {
  private final Map<Integer, List<MethodNode>> methodByLine = new HashMap<>();
  private final Map<String, Integer> methodStarts = new HashMap<>();
  private final TreeMap<Integer, LabelNode> lineLabels = new TreeMap<>();

  public ClassFileLines(ClassNode classNode) {
    for (MethodNode methodNode : classNode.methods) {
      AbstractInsnNode currentNode = methodNode.instructions.getFirst();
      while (currentNode != null) {
        if (currentNode.getType() == AbstractInsnNode.LINE) {
          LineNumberNode lineNode = (LineNumberNode) currentNode;
          methodStarts.putIfAbsent(methodNode.name + methodNode.desc, lineNode.line);
          // on the same line, we can have multiple methods (lambdas, inner classes, etc)
          List<MethodNode> methodNodes =
              methodByLine.computeIfAbsent(lineNode.line, k -> new ArrayList<>());
          // We are not using a Set here to keep the order of the methods
          // We assume also that the number of methods per line is small
          if (!methodNodes.contains(methodNode)) {
            methodNodes.add(methodNode);
          }
          // we are putting the line only the first time in the map.
          // for-loops can generate 2 line nodes for the same line, 1 before the loop, 1 for the
          // incrementation part.
          // We consider here that we want to place the probe before the loop.
          lineLabels.putIfAbsent(lineNode.line, lineNode.start);
        }
        currentNode = currentNode.getNext();
      }
    }
  }

  public int getMethodStart(MethodNode node) {
    return methodStarts.getOrDefault(node.name + node.desc, -1);
  }

  public List<MethodNode> getMethodsByLine(int line) {
    return methodByLine.get(line);
  }

  public LabelNode getLineLabel(int line) {
    Map.Entry<Integer, LabelNode> nextLine = lineLabels.ceilingEntry(line);
    if (nextLine == null) return null;
    return nextLine.getValue();
  }

  public boolean isEmpty() {
    return lineLabels.isEmpty();
  }
}
