package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.Trie.reverseStr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles class matching logic for probe definition */
public class TransformerDefinitionMatcher {
  private static final Logger LOG = LoggerFactory.getLogger(TransformerDefinitionMatcher.class);

  private final Map<String, List<ProbeDefinition>> definitionsByClass;
  private final Map<String, List<ProbeDefinition>> definitionsByFileNames;
  private final Trie definitionFileNames;
  private final Trie definitionsDirectories;

  public TransformerDefinitionMatcher(Configuration configuration) {
    this.definitionsByClass = buildDefinitionsMap(configuration.getDefinitions());
    this.definitionsByFileNames = buildDefinitionFileNamesMap(configuration.getDefinitions());
    this.definitionFileNames = buildDefinitionFileNamesTrie(definitionsByFileNames);
    this.definitionsDirectories = buildDefinitionsDirTrie(configuration.getDefinitions());
  }

  private Map<String, List<ProbeDefinition>> buildDefinitionsMap(
      Collection<ProbeDefinition> definitions) {
    Map<String, List<ProbeDefinition>> map = new HashMap<>();
    for (ProbeDefinition definition : definitions) {
      String className = definition.getWhere().getTypeName();
      if (className == null || className.equals("")) {
        className = definition.getWhere().getSourceFile();
        if (className == null || className.equals("")) {
          LOG.warn("Debugger definition {} cannot be prepared for matching", definition.getId());
          continue;
        }
        if (className.endsWith(".groovy")) {
          // special case for groovy because script does not have a proper source file name
          // so we are relying solely on filename without extension to match
          className = className.substring(0, className.length() - ".groovy".length());
        }
      }
      List<ProbeDefinition> definitionByClass =
          map.computeIfAbsent(className, key -> new ArrayList<>());
      definitionByClass.add(definition);
    }
    return map;
  }

  private Map<String, List<ProbeDefinition>> buildDefinitionFileNamesMap(
      Collection<ProbeDefinition> definitions) {
    Map<String, List<ProbeDefinition>> resultMap = new HashMap<>();
    for (ProbeDefinition definition : definitions) {
      String fileName = definition.getWhere().getSourceFile();
      if (fileName == null) {
        continue;
      }
      List<ProbeDefinition> definitionByFileName =
          resultMap.computeIfAbsent(fileName, key -> new ArrayList<>());
      definitionByFileName.add(definition);
    }
    return resultMap;
  }

  private Trie buildDefinitionFileNamesTrie(
      Map<String, List<ProbeDefinition>> definitionsByFileNames) {
    Trie resultTrie = new Trie();
    // Build a prefix trie by reversing filenames in probe definitions
    for (Map.Entry<String, List<ProbeDefinition>> entry : definitionsByFileNames.entrySet()) {
      String sourceFile = entry.getKey();
      resultTrie.insert(reverseStr(sourceFile));
    }
    return resultTrie;
  }

  private Trie buildDefinitionsDirTrie(Collection<ProbeDefinition> definitions) {
    Trie trie = new Trie();
    for (ProbeDefinition definition : definitions) {
      String fileName = definition.getWhere().getSourceFile();
      if (fileName == null) {
        continue;
      }
      int idx = fileName.lastIndexOf('/');
      String dir = idx > -1 ? fileName.substring(0, idx) : fileName;
      trie.insert(reverseStr(dir));
    }
    return trie;
  }

  public boolean isEmpty() {
    return definitionsByClass.isEmpty();
  }

  public List<ProbeDefinition> match(
      Class<?> classBeingRedefined, String classFilePath, String typeName, byte[] classfileBuffer) {
    List<ProbeDefinition> byTypeDefinitions =
        matchProbeDefinitionsByType(classBeingRedefined, typeName);
    List<ProbeDefinition> results = new ArrayList<>(byTypeDefinitions);
    List<ProbeDefinition> bySourceFileDefinitions =
        matchProbeDefinitionsBySourceFile(classFilePath, classfileBuffer);
    results.addAll(bySourceFileDefinitions);
    return results;
  }

  private List<ProbeDefinition> matchProbeDefinitionsByType(
      Class<?> classBeingRedefined, String typeName) {
    List<ProbeDefinition> byTypeDefinitions = new ArrayList<>();
    // try matching on FQN (java.lang.String)
    List<ProbeDefinition> definitions = definitionsByClass.get(typeName);
    if (definitions != null) {
      byTypeDefinitions.addAll(definitions);
    }
    // fallback to matching on SimpleName (String)
    String simpleClassName =
        classBeingRedefined != null
            ? classBeingRedefined.getSimpleName()
            : typeName.substring(typeName.lastIndexOf('.') + 1); // strip the package name
    if (typeName.equals(simpleClassName)) {
      return byTypeDefinitions;
    }
    definitions = definitionsByClass.get(simpleClassName);
    if (definitions != null) {
      byTypeDefinitions.addAll(definitions);
    }
    return byTypeDefinitions;
  }

  private List<ProbeDefinition> matchProbeDefinitionsBySourceFile(
      String className, byte[] classfileBuffer) {
    // try to match filename, need to retrieve the source filename from classfile
    // first we try match the package name to determine if it makes sense to parse byte code
    String reversedClassName = reverseStr(className);
    int idx = reversedClassName.indexOf('/');
    boolean hasPackageName = idx > -1;
    String reversedPackageName = reversedClassName.substring(idx + 1);
    // Retrieve the actual source file name
    // TODO maybe by scanning the byte array directly we can avoid doing an expensive parsing
    ClassReader reader = new ClassReader(classfileBuffer);
    ClassNode classNode = new ClassNode();
    reader.accept(classNode, ClassReader.SKIP_FRAMES);
    String sourceFileName = classNode.sourceFile;
    if (sourceFileName == null) {
      return Collections.emptyList();
    }
    String reversedSourceFileName = reverseStr(sourceFileName);
    StringBuilder sb = new StringBuilder(reversedSourceFileName);
    if (hasPackageName) {
      sb.append('/').append(reversedPackageName);
    }
    String reversedFileName = sb.toString();
    String fullFileName = reverseStr(definitionFileNames.getStringStartingWith(reversedFileName));
    if (fullFileName == null) {
      fullFileName = reverseStr(definitionFileNames.getStringStartingWith(reversedSourceFileName));
    }
    if (fullFileName == null) {
      return Collections.emptyList();
    }
    List<ProbeDefinition> bySourceFileDefinitions = definitionsByFileNames.get(fullFileName);
    if (bySourceFileDefinitions != null) {
      return bySourceFileDefinitions;
    }
    return Collections.emptyList();
  }
}
