package com.datadog.debugger.util;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

public class ClassFileLines {
  private final Map<Integer, MethodNode> methodByLine = new HashMap<>();
  private final TreeMap<Integer, LabelNode> lineLabels = new TreeMap<>();

  public ClassFileLines(ClassNode classNode) {
    for (MethodNode methodNode : classNode.methods) {
      AbstractInsnNode currentNode = methodNode.instructions.getFirst();
      while (currentNode != null) {
        if (currentNode.getType() == AbstractInsnNode.LINE) {
          LineNumberNode lineNode = (LineNumberNode) currentNode;
          methodByLine.put(lineNode.line, methodNode);
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

  public MethodNode getMethodByLine(int line) {
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
