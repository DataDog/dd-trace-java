package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.Trie.reverseStr;

import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.util.ClassFileHelper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles class matching logic for probe definition */
public class TransformerDefinitionMatcher {
  private static final Logger LOG = LoggerFactory.getLogger(TransformerDefinitionMatcher.class);

  private final Map<String, List<ProbeDefinition>> definitionsByClass;
  private final Map<String, List<ProbeDefinition>> definitionsBySimpleFileNames = new HashMap<>();
  private final Map<String, List<ProbeDefinition>> definitionsByQualifiedFileNames =
      new HashMap<>();
  private final Trie definitionFileNames;

  public TransformerDefinitionMatcher(Configuration configuration) {
    this.definitionsByClass = buildDefinitionsMap(configuration.getDefinitions());
    populateDefinitionFileNamesMap(configuration.getDefinitions());
    this.definitionFileNames = buildDefinitionFileNamesTrie(definitionsByQualifiedFileNames);
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

  private void populateDefinitionFileNamesMap(Collection<ProbeDefinition> definitions) {
    for (ProbeDefinition definition : definitions) {
      String fileName = definition.getWhere().getSourceFile();
      if (fileName == null) {
        continue;
      }
      Map<String, List<ProbeDefinition>> targetMap =
          fileName.indexOf('/') != -1
              ? definitionsByQualifiedFileNames
              : definitionsBySimpleFileNames;
      targetMap.computeIfAbsent("/" + fileName, key -> new ArrayList<>()).add(definition);
    }
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
    String reversedClassName = reverseStr(className);
    int idx = reversedClassName.indexOf('/');
    boolean hasPackageName = idx > -1;
    String reversedPackageName = reversedClassName.substring(idx + 1);
    // Retrieve the actual source file name
    String sourceFileName = ClassFileHelper.extractSourceFile(classfileBuffer);
    if (sourceFileName == null) {
      return Collections.emptyList();
    }
    String reversedSourceFileName = reverseStr("/" + sourceFileName);
    StringBuilder sb = new StringBuilder(reversedSourceFileName);
    if (hasPackageName) {
      sb.append(reversedPackageName);
    }
    String reversedFileName = sb.toString();
    List<ProbeDefinition> bySourceFileDefinitions = new ArrayList<>();
    // try match qualified filenames
    Collection<String> matchingFileNames =
        definitionFileNames.getStringsStartingWith(reversedFileName);
    if (!matchingFileNames.isEmpty()) {
      for (String matchingFileName : matchingFileNames) {
        List<ProbeDefinition> definitions =
            definitionsByQualifiedFileNames.get(reverseStr(matchingFileName));
        if (definitions != null) {
          bySourceFileDefinitions.addAll(definitions);
        }
      }
    }
    // try match simple filenames
    List<ProbeDefinition> definitions = definitionsBySimpleFileNames.get("/" + sourceFileName);
    if (definitions != null) {
      bySourceFileDefinitions.addAll(definitions);
    }
    return bySourceFileDefinitions;
  }
}
