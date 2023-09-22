package com.datadog.debugger.symbol;

import static com.datadog.debugger.instrumentation.ASMHelper.adjustLocalVarsBasedOnArgs;
import static com.datadog.debugger.instrumentation.ASMHelper.createLocalVarNodes;
import static com.datadog.debugger.instrumentation.ASMHelper.sortLocalVariables;

import com.datadog.debugger.instrumentation.ASMHelper;
import datadog.trace.util.Strings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.LoggerFactory;

public class SymbolExtractor {

  public static Scope extract(byte[] classFileBuffer, String jarName) {
    ClassNode classNode = parseClassFile(classFileBuffer);
    return extractScopes(classNode, jarName);
  }

  private static Scope extractScopes(ClassNode classNode, String jarName) {
    try {
      List<Scope> methodScopes = new ArrayList<>();
      for (MethodNode method : classNode.methods) {
        MethodLineInfo methodLineInfo = extractMethodLineInfo(method);
        List<Scope> varScopes = new ArrayList<>();
        List<Symbol> methodSymbols = new ArrayList<>();
        int localVarBaseSlot = extractArgs(method, methodSymbols, methodLineInfo.start);
        extractScopesFromVariables(
            classNode.sourceFile, method, methodLineInfo.lineMap, varScopes, localVarBaseSlot);
        Scope methodScope =
            Scope.builder(
                    ScopeType.METHOD,
                    classNode.sourceFile,
                    methodLineInfo.start,
                    methodLineInfo.end)
                .name(method.name)
                .scopes(varScopes)
                .symbols(methodSymbols)
                .build();
        methodScopes.add(methodScope);
      }
      int classStartLine = Integer.MAX_VALUE;
      int classEndLine = 0;
      for (Scope scope : methodScopes) {
        classStartLine = Math.min(classStartLine, scope.getStartLine());
        classEndLine = Math.max(classEndLine, scope.getEndLine());
      }
      List<Symbol> fields = new ArrayList<>();
      for (FieldNode fieldNode : classNode.fields) {
        SymbolType symbolType =
            ASMHelper.isStaticField(fieldNode) ? SymbolType.STATIC_FIELD : SymbolType.FIELD;
        fields.add(
            new Symbol(symbolType, fieldNode.name, 0, Type.getType(fieldNode.desc).getClassName()));
      }
      Scope classScope =
          Scope.builder(ScopeType.CLASS, classNode.sourceFile, classStartLine, classEndLine)
              .name(Strings.getClassName(classNode.name))
              .scopes(methodScopes)
              .symbols(fields)
              .build();
      return Scope.builder(ScopeType.JAR, jarName, 0, 0)
          .name(jarName)
          .scopes(new ArrayList<>(Collections.singletonList(classScope)))
          .build();
    } catch (Exception ex) {
      LoggerFactory.getLogger(SymbolExtractor.class).info("", ex);
      return null;
    }
  }

  private static int extractArgs(
      MethodNode method, List<Symbol> methodSymbols, int methodStartLine) {
    boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
    int slot = isStatic ? 0 : 1;
    if (method.localVariables == null || method.localVariables.size() == 0) {
      return slot;
    }
    Type[] argTypes = Type.getArgumentTypes(method.desc);
    if (argTypes.length == 0) {
      return slot;
    }
    List<LocalVariableNode> sortedLocalVars = sortLocalVariables(method.localVariables);
    LocalVariableNode[] localVarsBySlot = createLocalVarNodes(sortedLocalVars);
    adjustLocalVarsBasedOnArgs(isStatic, localVarsBySlot, argTypes, sortedLocalVars);
    for (Type argType : argTypes) {
      if (slot >= localVarsBySlot.length) {
        break;
      }
      String argName = localVarsBySlot[slot] != null ? localVarsBySlot[slot].name : "p" + slot;
      methodSymbols.add(
          new Symbol(SymbolType.ARG, argName, methodStartLine, argType.getClassName()));
      slot += argType.getSize();
    }
    return slot;
  }

  private static void extractScopesFromVariables(
      String sourceFile,
      MethodNode methodNode,
      Map<Label, Integer> monotonicLineMap,
      List<Scope> varScopes,
      int locaVarBaseSlot) {
    if (methodNode.localVariables == null) {
      return;
    }
    // using a LinkedHashMap only for having a stable order of local scopes (tests)
    Map<LabelNode, List<LocalVariableNode>> varsByEndLabel = new LinkedHashMap<>();
    for (int i = locaVarBaseSlot; i < methodNode.localVariables.size(); i++) {
      LocalVariableNode localVariable = methodNode.localVariables.get(i);
      varsByEndLabel.merge(
          localVariable.end,
          new ArrayList<>(Collections.singletonList(localVariable)),
          (curr, next) -> {
            curr.addAll(next);
            return curr;
          });
    }
    for (Map.Entry<LabelNode, List<LocalVariableNode>> entry : varsByEndLabel.entrySet()) {
      List<Symbol> varSymbols = new ArrayList<>();
      int minLine = Integer.MAX_VALUE;
      for (LocalVariableNode var : entry.getValue()) {
        int line = monotonicLineMap.get(var.start.getLabel());
        minLine = Math.min(line, minLine);
        varSymbols.add(
            new Symbol(SymbolType.LOCAL, var.name, line, Type.getType(var.desc).getClassName()));
      }
      int endLine = monotonicLineMap.get(entry.getKey().getLabel());
      Scope varScope =
          Scope.builder(ScopeType.LOCAL, sourceFile, minLine, endLine).symbols(varSymbols).build();
      varScopes.add(varScope);
    }
  }

  private static int getFirstLine(MethodNode methodNode) {
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

  private static MethodLineInfo extractMethodLineInfo(MethodNode methodNode) {
    Map<Label, Integer> map = new HashMap<>();
    int startLine = getFirstLine(methodNode);
    int maxLine = startLine;
    AbstractInsnNode node = methodNode.instructions.getFirst();
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
    return new MethodLineInfo(startLine, maxLine, map);
  }

  private static ClassNode parseClassFile(byte[] classfileBuffer) {
    ClassReader reader = new ClassReader(classfileBuffer);
    ClassNode classNode = new ClassNode();
    reader.accept(classNode, ClassReader.SKIP_FRAMES);
    return classNode;
  }

  public static class MethodLineInfo {
    final int start;
    final int end;
    final Map<Label, Integer> lineMap;

    public MethodLineInfo(int start, int end, Map<Label, Integer> lineMap) {
      this.start = start;
      this.end = end;
      this.lineMap = lineMap;
    }
  }
}
