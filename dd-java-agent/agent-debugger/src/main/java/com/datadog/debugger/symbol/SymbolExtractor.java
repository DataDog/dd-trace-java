package com.datadog.debugger.symbol;

import static com.datadog.debugger.instrumentation.ASMHelper.adjustLocalVarsBasedOnArgs;
import static com.datadog.debugger.instrumentation.ASMHelper.createLocalVarNodes;
import static com.datadog.debugger.instrumentation.ASMHelper.sortLocalVariables;

import com.datadog.debugger.instrumentation.ASMHelper;
import datadog.trace.agent.tooling.stratum.SourceMap;
import datadog.trace.agent.tooling.stratum.parser.Parser;
import datadog.trace.util.Strings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolExtractor {
  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolExtractor.class);

  public static Scope extract(byte[] classFileBuffer, String jarName) {
    ClassNode classNode = parseClassFile(classFileBuffer);
    return extractScopes(classNode, jarName);
  }

  private static Scope extractScopes(ClassNode classNode, String jarName) {
    try {
      String sourceFile = extractSourceFile(classNode);
      SourceRemapper sourceRemapper = SourceRemapper.NOOP_REMAPPER;
      if (classNode.sourceDebug != null) {
        List<SourceMap> sourceMaps = Parser.parse(classNode.sourceDebug);
        if (sourceMaps.isEmpty()) {
          throw new IllegalStateException("No source maps found for " + classNode.name);
        }
        SourceMap sourceMap = sourceMaps.get(0);
        sourceRemapper = SourceRemapper.getSourceRemapper(classNode.sourceFile, sourceMap);
      }
      List<Scope> methodScopes = extractMethods(classNode, sourceFile, sourceRemapper);
      int classStartLine = Integer.MAX_VALUE;
      int classEndLine = 0;
      for (Scope scope : methodScopes) {
        classStartLine = Math.min(classStartLine, scope.getStartLine());
        classEndLine = Math.max(classEndLine, scope.getEndLine());
      }
      List<Symbol> fields = extractFields(classNode);
      LanguageSpecifics classSpecifics =
          new LanguageSpecifics.Builder()
              .addModifiers(extractClassModifiers(classNode.access))
              .addInterfaces(extractInterfaces(classNode))
              .addAnnotations(extractAnnotations(classNode.visibleAnnotations))
              .superClass(ASMHelper.extractSuperClass(classNode))
              .build();
      Scope classScope =
          Scope.builder(ScopeType.CLASS, sourceFile, classStartLine, classEndLine)
              .name(Strings.getClassName(classNode.name))
              .scopes(methodScopes)
              .symbols(fields)
              .languageSpecifics(classSpecifics)
              .build();
      return Scope.builder(ScopeType.JAR, jarName, 0, 0)
          .name(jarName)
          .scopes(new ArrayList<>(Collections.singletonList(classScope)))
          .build();
    } catch (Exception ex) {
      LOGGER.debug(
          "Extracting scopes for class[{}] in jar[{}] failed: ", classNode.name, jarName, ex);
      return null;
    }
  }

  private static Collection<String> extractInterfaces(ClassNode classNode) {
    if (classNode.interfaces.isEmpty()) {
      return Collections.emptyList();
    }
    return classNode.interfaces.stream().map(Strings::getClassName).collect(Collectors.toList());
  }

  private static List<Symbol> extractFields(ClassNode classNode) {
    List<Symbol> fields = new ArrayList<>();
    for (FieldNode fieldNode : classNode.fields) {
      SymbolType symbolType =
          ASMHelper.isStaticField(fieldNode) ? SymbolType.STATIC_FIELD : SymbolType.FIELD;
      LanguageSpecifics fieldSpecifics =
          new LanguageSpecifics.Builder()
              .addModifiers(extractFieldModifiers(fieldNode.access))
              .addAnnotations(extractAnnotations(fieldNode.visibleAnnotations))
              .build();
      fields.add(
          new Symbol(
              symbolType,
              fieldNode.name,
              0,
              Type.getType(fieldNode.desc).getClassName(),
              fieldSpecifics));
    }
    return fields;
  }

  private static List<Scope> extractMethods(
      ClassNode classNode, String sourceFile, SourceRemapper sourceRemapper) {
    List<Scope> methodScopes = new ArrayList<>();
    for (MethodNode method : classNode.methods) {
      MethodLineInfo methodLineInfo = extractMethodLineInfo(method, sourceRemapper);
      List<Scope> varScopes = new ArrayList<>();
      List<Symbol> methodSymbols = new ArrayList<>();
      int localVarBaseSlot = extractArgs(method, methodSymbols, methodLineInfo.start);
      extractScopesFromVariables(
          sourceFile, method, methodLineInfo.lineMap, varScopes, localVarBaseSlot);
      ScopeType methodScopeType = ScopeType.METHOD;
      if (method.name.startsWith("lambda$")) {
        methodScopeType = ScopeType.CLOSURE;
      }
      LanguageSpecifics methodSpecifics =
          new LanguageSpecifics.Builder()
              .addModifiers(extractMethodModifiers(classNode, method, method.access))
              .addAnnotations(extractAnnotations(method.visibleAnnotations))
              .returnType(Type.getType(method.desc).getReturnType().getClassName())
              .build();
      Scope methodScope =
          Scope.builder(methodScopeType, sourceFile, methodLineInfo.start, methodLineInfo.end)
              .name(method.name)
              .scopes(varScopes)
              .symbols(methodSymbols)
              .hasInjectibleLines(!methodLineInfo.ranges.isEmpty())
              .injectibleLines(methodLineInfo.ranges)
              .languageSpecifics(methodSpecifics)
              .build();
      methodScopes.add(methodScope);
    }
    return methodScopes;
  }

  private static Collection<String> extractClassModifiers(int access) {
    List<String> results = new ArrayList<>();
    for (int remaining = access, bit; remaining != 0; remaining -= bit) {
      bit = Integer.lowestOneBit(remaining);
      switch (bit) {
        case Opcodes.ACC_PUBLIC:
          results.add("public");
          break;
        case Opcodes.ACC_PRIVATE:
          results.add("private");
          break;
        case Opcodes.ACC_PROTECTED:
          results.add("protected");
          break;
        case Opcodes.ACC_STATIC:
          results.add("static");
          break;
        case Opcodes.ACC_FINAL:
          results.add("final");
          break;
        case Opcodes.ACC_SUPER:
          break; // not interesting
        case Opcodes.ACC_INTERFACE:
          results.add("interface");
          break;
        case Opcodes.ACC_ABSTRACT:
          results.add("abstract");
          break;
        case Opcodes.ACC_SYNTHETIC:
          results.add("synthetic");
          break;
        case Opcodes.ACC_ANNOTATION:
          results.add("annotation");
          break;
        case Opcodes.ACC_ENUM:
          results.add("enum");
          break;
        case Opcodes.ACC_MODULE:
          results.add("module");
          break;
        case Opcodes.ACC_RECORD:
          results.add("record");
          break;
        case Opcodes.ACC_DEPRECATED:
          results.add("deprecated");
          break;
        default:
          LOGGER.debug("Invalid class access modifiers: {}", bit);
      }
    }
    return results;
  }

  private static Collection<String> extractMethodModifiers(
      ClassNode classNode, MethodNode methodNode, int access) {
    List<String> results = new ArrayList<>();
    for (int remaining = access, bit; remaining != 0; remaining -= bit) {
      bit = Integer.lowestOneBit(remaining);
      switch (bit) {
        case Opcodes.ACC_PUBLIC:
          results.add("public");
          break;
        case Opcodes.ACC_PRIVATE:
          results.add("private");
          break;
        case Opcodes.ACC_PROTECTED:
          results.add("protected");
          break;
        case Opcodes.ACC_STATIC:
          results.add("static");
          break;
        case Opcodes.ACC_FINAL:
          results.add("final");
          break;
        case Opcodes.ACC_SYNCHRONIZED:
          results.add("synchronized");
          break;
        case Opcodes.ACC_BRIDGE:
          results.add("(bridge)");
          break;
        case Opcodes.ACC_VARARGS:
          results.add("(varargs)");
          break;
        case Opcodes.ACC_NATIVE:
          results.add("native");
          break;
        case Opcodes.ACC_ABSTRACT:
          results.add("abstract");
          break;
        case Opcodes.ACC_STRICT:
          results.add("strictfp");
          break;
        case Opcodes.ACC_SYNTHETIC:
          results.add("synthetic");
          break;
        case Opcodes.ACC_MANDATED:
          results.add("mandated");
          break;
        case Opcodes.ACC_DEPRECATED:
          results.add("deprecated");
          break;
        default:
          LOGGER.debug(
              "Invalid access modifiers method[{}::{}]: {}",
              classNode.name,
              methodNode.name + methodNode.desc,
              bit);
      }
    }
    // if class is an interface && method has code && non-static this is a default method
    if ((classNode.access & Opcodes.ACC_INTERFACE) > 0
        && methodNode.instructions.size() > 0
        && (methodNode.access & Opcodes.ACC_STATIC) == 0) {
      results.add("default");
    }
    return results;
  }

  private static Collection<String> extractFieldModifiers(int access) {
    List<String> results = new ArrayList<>();
    for (int remaining = access, bit; remaining != 0; remaining -= bit) {
      bit = Integer.lowestOneBit(remaining);
      switch (bit) {
        case Opcodes.ACC_PUBLIC:
          results.add("public");
          break;
        case Opcodes.ACC_PRIVATE:
          results.add("private");
          break;
        case Opcodes.ACC_PROTECTED:
          results.add("protected");
          break;
        case Opcodes.ACC_STATIC:
          results.add("static");
          break;
        case Opcodes.ACC_FINAL:
          results.add("final");
          break;
        case Opcodes.ACC_VOLATILE:
          results.add("volatile");
          break;
        case Opcodes.ACC_TRANSIENT:
          results.add("transient");
          break;
        case Opcodes.ACC_SYNTHETIC:
          results.add("synthetic");
          break;
        case Opcodes.ACC_ENUM:
          results.add("enum");
          break;
        case Opcodes.ACC_MANDATED:
          results.add("mandated");
          break;
        case Opcodes.ACC_DEPRECATED:
          results.add("deprecated");
          break;
        default:
          LOGGER.debug("Invalid access modifiers: {}", bit);
      }
    }
    return results;
  }

  private static Collection<String> extractAnnotations(List<AnnotationNode> annotationNodes) {
    if (annotationNodes == null || annotationNodes.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> results = new ArrayList<>();
    for (AnnotationNode annotationNode : annotationNodes) {
      StringBuilder sb = new StringBuilder("@");
      sb.append(Type.getType(annotationNode.desc).getClassName());
      results.add(sb.toString());
    }
    return results;
  }

  private static String extractSourceFile(ClassNode classNode) {
    String packageName = classNode.name;
    int idx = packageName.lastIndexOf('/');
    packageName = idx >= 0 ? packageName.substring(0, idx + 1) : "";
    return packageName + classNode.sourceFile;
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
          new Symbol(SymbolType.ARG, argName, methodStartLine, argType.getClassName(), null));
      slot += argType.getSize();
    }
    return slot;
  }

  private static void extractScopesFromVariables(
      String sourceFile,
      MethodNode methodNode,
      Map<Label, Integer> monotonicLineMap,
      List<Scope> varScopes,
      int localVarBaseSlot) {
    if (methodNode.localVariables == null) {
      return;
    }
    // using a LinkedHashMap only for having a stable order of local scopes (tests)
    Map<LabelNode, List<LocalVariableNode>> varsByEndLabel = new LinkedHashMap<>();
    for (int i = 0; i < methodNode.localVariables.size(); i++) {
      LocalVariableNode localVariable = methodNode.localVariables.get(i);
      if (localVariable.index < localVarBaseSlot) {
        continue;
      }
      varsByEndLabel.merge(
          localVariable.end,
          new ArrayList<>(Collections.singletonList(localVariable)),
          (curr, next) -> {
            curr.addAll(next);
            return curr;
          });
    }
    List<Scope> tmpScopes = new ArrayList<>();
    for (Map.Entry<LabelNode, List<LocalVariableNode>> entry : varsByEndLabel.entrySet()) {
      List<Symbol> varSymbols = new ArrayList<>();
      int minLine = Integer.MAX_VALUE;
      for (LocalVariableNode var : entry.getValue()) {
        int line = monotonicLineMap.get(var.start.getLabel());
        minLine = Math.min(line, minLine);
        varSymbols.add(
            new Symbol(
                SymbolType.LOCAL, var.name, line, Type.getType(var.desc).getClassName(), null));
      }
      int endLine = monotonicLineMap.get(entry.getKey().getLabel());
      Scope varScope =
          Scope.builder(ScopeType.LOCAL, sourceFile, minLine, endLine)
              .symbols(varSymbols)
              .scopes(new ArrayList<>())
              .build();
      tmpScopes.add(varScope);
    }
    nestScopes(varScopes, tmpScopes);
  }

  private static Scope removeWidestScope(List<Scope> scopes) {
    Scope widestScope = null;
    for (Scope scope : scopes) {
      widestScope = widestScope != null ? maxScope(widestScope, scope) : scope;
    }
    // Remove the actual widest instance from the list, based on reference equality
    scopes.remove(widestScope);
    return widestScope;
  }

  private static void nestScopes(List<Scope> outerScopes, List<Scope> scopes) {
    Scope widestScope = removeWidestScope(scopes);
    if (widestScope == null) {
      return;
    }
    outerScopes.add(widestScope);
    for (Scope scope : scopes) {
      boolean added = false;
      for (Scope outerScope : outerScopes) {
        if (isInnerScope(outerScope, scope)) {
          outerScope.getScopes().add(scope);
          added = true;
          break;
        }
      }
      if (!added) {
        outerScopes.add(scope);
      }
    }
    for (Scope outerScope : outerScopes) {
      List<Scope> tmpScopes = new ArrayList<>(outerScope.getScopes());
      outerScope.getScopes().clear();
      nestScopes(outerScope.getScopes(), tmpScopes);
    }
  }

  private static boolean isInnerScope(Scope enclosingScope, Scope scope) {
    return scope.getStartLine() >= enclosingScope.getStartLine()
        && scope.getEndLine() <= enclosingScope.getEndLine();
  }

  private static Scope maxScope(Scope scope1, Scope scope2) {
    return scope1.getStartLine() > scope2.getStartLine()
            || scope1.getEndLine() < scope2.getEndLine()
        ? scope2
        : scope1;
  }

  static List<Scope.LineRange> buildRanges(List<Integer> sortedLineNo) {
    if (sortedLineNo.isEmpty()) {
      return Collections.emptyList();
    }
    List<Scope.LineRange> ranges = new ArrayList<>();
    int start = sortedLineNo.get(0);
    int previous = start;
    int i = 1;
    outer:
    while (i < sortedLineNo.size()) {
      int currentLineNo = sortedLineNo.get(i);
      while (currentLineNo == previous + 1) {
        i++;
        previous++;
        if (i < sortedLineNo.size()) {
          currentLineNo = sortedLineNo.get(i);
        } else {
          break outer;
        }
      }
      ranges.add(new Scope.LineRange(start, previous));
      start = currentLineNo;
      previous = start;
      i++;
    }
    ranges.add(new Scope.LineRange(start, previous));
    return ranges;
  }

  private static MethodLineInfo extractMethodLineInfo(
      MethodNode methodNode, SourceRemapper sourceRemapper) {
    Map<Label, Integer> map = new HashMap<>();
    List<Integer> lineNo = new ArrayList<>();
    Set<Integer> dedupSet = new HashSet<>();
    AbstractInsnNode node = methodNode.instructions.getFirst();
    int maxLine = 0;
    while (node != null) {
      if (node.getType() == AbstractInsnNode.LINE) {
        LineNumberNode lineNumberNode = (LineNumberNode) node;
        int newLine = sourceRemapper.remapSourceLine(lineNumberNode.line);
        if (dedupSet.add(newLine)) {
          lineNo.add(newLine);
        }
        maxLine = Math.max(newLine, maxLine);
      }
      if (node.getType() == AbstractInsnNode.LABEL) {
        if (node instanceof LabelNode) {
          LabelNode labelNode = (LabelNode) node;
          map.put(labelNode.getLabel(), maxLine);
        }
      }
      node = node.getNext();
    }
    lineNo.sort(Integer::compareTo);
    int startLine = lineNo.isEmpty() ? 0 : lineNo.get(0);
    List<Scope.LineRange> ranges = buildRanges(lineNo);
    return new MethodLineInfo(startLine, maxLine, map, ranges);
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
    final List<Scope.LineRange> ranges;

    public MethodLineInfo(
        int start, int end, Map<Label, Integer> lineMap, List<Scope.LineRange> ranges) {
      this.start = start;
      this.end = end;
      this.lineMap = lineMap;

      this.ranges = ranges;
    }
  }
}
