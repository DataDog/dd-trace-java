package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.Trie.reverseStr;
import static com.datadog.debugger.agent.TypeNameHelper.extractSimpleName;
import static com.datadog.debugger.util.ClassFileHelper.normalizeFilePath;
import static com.datadog.debugger.util.ClassFileHelper.removeExtension;
import static com.datadog.debugger.util.ClassFileHelper.stripPackagePath;

import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.ProbeDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClassesToRetransformFinder {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClassesToRetransformFinder.class);
  private static final Pattern COMMA_PATTERN = Pattern.compile(",");

  private final ConcurrentMap<String, String> classNamesBySourceFile = new ConcurrentHashMap<>();

  public void register(String sourceFile, String className) {
    // store only the class name that are different from SourceFile name
    // (Inner or non-public Top-Level classes)
    classNamesBySourceFile.compute(
        sourceFile,
        (key, classNames) -> {
          if (classNames == null) {
            return className;
          }
          return classNames + "," + className;
        });
  }

  public List<Class<?>> getAllLoadedChangedClasses(
      Class<?>[] allLoadedClasses, ConfigurationComparer comparer) {
    List<Class<?>> classesToBeTransformed = new ArrayList<>();
    Trie changedClasses = getAllChangedClasses(comparer);
    for (Class<?> clazz : allLoadedClasses) {
      if (clazz == null) {
        // clazz could be null in the array returned by Instrumentation::getAllLoadedClasses
        // probably because class unloading
        continue;
      }
      if (lookupClass(changedClasses, clazz)) {
        classesToBeTransformed.add(clazz);
      }
    }
    return classesToBeTransformed;
  }

  public boolean hasChangedClasses(ConfigurationComparer comparer) {
    return !getAllChangedClasses(comparer).isEmpty();
  }

  Trie getAllChangedClasses(ConfigurationComparer comparer) {
    List<ProbeDefinition> changedDefinitions =
        Stream.concat(
                comparer.getRemovedDefinitions().stream(), comparer.getAddedDefinitions().stream())
            .collect(Collectors.toList());
    Trie changedClasses = new Trie();
    for (ProbeDefinition definition : changedDefinitions) {
      InstrumentationResult instrumentationResult =
          comparer.getInstrumentationResults().get(definition.getProbeId().getEncodedId());
      String key = instrumentationResult != null ? instrumentationResult.getTypeName() : null;
      if (key == null || key.equals("")) {
        key = definition.getWhere().getTypeName();
        if (key == null || key.equals("")) {
          key = definition.getWhere().getSourceFile();
          if (key == null || key.equals("")) {
            continue;
          }
          processAdditionalClasses(key, changedClasses);
          key = removeExtension(key);
          key = normalizeFilePath(key);
        }
      } else {
        key = normalizeFilePath(key);
      }
      LOGGER.debug("instrumented class changed: {} for probe id: {}", key, definition.getId());
      changedClasses.insert(reverseStr(key));
    }
    for (String typeName : comparer.getChangedBlockedTypes()) {
      LOGGER.debug("blocked class found: {}", typeName);
      changedClasses.insert(reverseStr(typeName));
    }
    return changedClasses;
  }

  private void processAdditionalClasses(String sourceFile, Trie changedClasses) {
    sourceFile = stripPackagePath(sourceFile);
    String classNames = classNamesBySourceFile.get(sourceFile);
    if (classNames == null) {
      return;
    }
    List<String> additionalClasses = Arrays.asList(COMMA_PATTERN.split(classNames));
    for (String additionalClass : additionalClasses) {
      additionalClass = normalizeFilePath(additionalClass);
      changedClasses.insert(reverseStr(additionalClass));
    }
  }

  private static boolean lookupClass(Trie changedClasses, Class<?> clazz) {
    String reversedTypeName = reverseStr(clazz.getTypeName());
    // try first with FQN (java.lang.String)
    if (changedClasses.containsPrefix(reversedTypeName)) {
      return true;
    }
    // fallback to matching on SimpleName (String)
    String simpleName = extractSimpleName(clazz);
    return changedClasses.contains(reverseStr(simpleName));
  }

  ConcurrentMap<String, String> getClassNamesBySourceFile() {
    return classNamesBySourceFile;
  }
}
