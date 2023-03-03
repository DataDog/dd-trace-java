package com.datadog.debugger.instrumentation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SymbolExtractor {

  private final ClassExtraction classExtraction;

  public SymbolExtractor(String classFilePath, byte[] classFileBuffer) {
    ClassNode classNode = parseClassFile(classFilePath, classFileBuffer);
    this.classExtraction = extractScopes(classNode);
    System.out.println();
  }

  private ClassExtraction extractScopes(ClassNode classNode) {
    List<MethodExtraction> methods = new ArrayList<>();
    for (MethodNode method : classNode.methods) {
      List<Block> scopes = extractScopesFromVariables(method);
      MethodExtraction methodExtraction = new MethodExtraction(method.name, scopes);
      methods.add(methodExtraction);
    }
    return new ClassExtraction(classNode.name, classNode.sourceFile, methods);
  }

  private List<Block> extractScopesFromVariables(MethodNode methodNode) {
    Map<Label, Integer> monotonicLineMap = getMonotonicLineMap(methodNode);
    List<Block> blocks = new ArrayList<>();
    Map<LabelNode, List<LocalVariableNode>> varsByEndLabel = new HashMap<>();
    for (LocalVariableNode localVariable : methodNode.localVariables) {
      varsByEndLabel.merge(localVariable.end, new ArrayList<>(Collections.singletonList(localVariable)), (curr, next) -> {
        curr.addAll(next);
        return curr;
      });
    }

    for (Map.Entry<LabelNode, List<LocalVariableNode>> entry : varsByEndLabel.entrySet()) {
      List<Variable> variables = new ArrayList<>();
      int minLine = Integer.MAX_VALUE;
      for (LocalVariableNode var : entry.getValue()) {
        int line = monotonicLineMap.get(var.start.getLabel());
        minLine = Math.min(line, minLine);
        variables.add(new Variable(var.name, line));
      }
      int endLine = monotonicLineMap.get(entry.getKey().getLabel());
      blocks.add(new Block(minLine, endLine, variables));
    }

    return blocks;
  }

  private int getFirstLine(MethodNode methodNode) {
    AbstractInsnNode node = methodNode.instructions.getFirst();
    while (node != null) {
      if (node.getType() == AbstractInsnNode.LINE) {
        LineNumberNode lineNumberNode = (LineNumberNode) node;
        return lineNumberNode.line;
      }
      node = node.getNext();
    }
    return 0;
  }

  private Map<Label, Integer> getMonotonicLineMap(MethodNode methodNode) {
    Map<Label, Integer> map = new HashMap<>();
    AbstractInsnNode node = methodNode.instructions.getFirst();
    int maxLine = getFirstLine(methodNode);
    while (node != null) {
      if (node.getType() == AbstractInsnNode.LINE) {
        LineNumberNode lineNumberNode = (LineNumberNode) node;
        maxLine = Math.max(lineNumberNode.line, maxLine);
      }
      if (node.getType() == AbstractInsnNode.LABEL) {
        if (node instanceof LabelNode) {
          LabelNode labelNode = (LabelNode) node;
          map.put(labelNode.getLabel(), maxLine);
        }
      }
      node = node.getNext();
    }
    return map;
  }

  static class Block {
    private final int startLine;
    private final int endLine;
    private final List<Variable> variables;

    Block(int startLine, int endLine, List<Variable> variables) {
      this.startLine = startLine;
      this.endLine = endLine;
      this.variables = variables;
    }

    public int getStartLine() {
      return startLine;
    }

    public int getEndLine() {
      return endLine;
    }

    @Override
    public String toString() {
      return "Block{" +
          "startLine=" + startLine +
          ", endLine=" + endLine +
          ", variables=" + variables +
          '}';
    }
  }

  static class Variable {
    private final String name;
    private final int line;

    Variable(String name, int line) {
      this.name = name;
      this.line = line;
    }

    public String getName() {
      return name;
    }

    public int getLine() {
      return line;
    }

    @Override
    public String toString() {
      return "Variable{" +
          "name='" + name + '\'' +
          ", line=" + line +
          '}';
    }
  }

  private ClassNode parseClassFile(String classFilePath, byte[] classfileBuffer) {
    ClassReader reader = new ClassReader(classfileBuffer);
    ClassNode classNode = new ClassNode();
    reader.accept(classNode, ClassReader.SKIP_FRAMES);
    return classNode;
  }

  public ClassExtraction getClassExtraction() {
    return classExtraction;
  }

  public static class ClassExtraction {

    private final String className;

    private final String sourcePath;

    private final int startLine = 1;

    private final List<MethodExtraction> methods;

    private ClassExtraction(String className, String sourcePath, List<MethodExtraction> methods) {
      this.className = className;
      this.sourcePath = sourcePath;
      this.methods = methods;
    }

    public List<MethodExtraction> getMethods() {
      return methods;
    }

    public String getClassName() {
      return className;
    }

    public String getSourcePath() {
      return sourcePath;
    }

    @Override
    public String toString() {
      return "ClassExtraction{" +
          "className='" + className + '\'' +
          ", sourcePath='" + sourcePath + '\'' +
          ", methods=" + methods +
          '}';
    }
  }

  public static class MethodExtraction {

    private final String methodName;

    private final List<Block> scopes;

    private MethodExtraction(String methodName, List<Block> scopes) {
      this.methodName = methodName;
      this.scopes = scopes;
    }

    public List<Block> getScopes() {
      return scopes;
    }

    public String getMethodName() {
      return methodName;
    }

    @Override
    public String toString() {
      return "MethodExtraction{" +
          "methodName='" + methodName + '\'' +
          ", scopes=" + scopes +
          '}';
    }
  }
}
