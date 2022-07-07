package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.Trie.reverseStr;
import static com.datadog.debugger.agent.TypeNameHelper.extractSimpleName;

import com.datadog.debugger.instrumentation.InstrumentationResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles comparison with current configuration and incoming new configuration Provides also list
 * of classes that need to be changed according to new definitions
 */
public class ConfigurationComparer {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationComparer.class);

  private Configuration originalConfiguration;
  private Configuration incomingConfiguration;

  private final List<ProbeDefinition> addedDefinitions;
  private final List<ProbeDefinition> removedDefinitions;
  private final boolean filteredListChanged;
  private final Map<String, InstrumentationResult> instrumentationResults;
  private final List<String> changedBlockedTypes;
  private Trie allChangedClasses;

  public ConfigurationComparer(
      Configuration originalConfiguration,
      Configuration incomingConfiguration,
      Map<String, InstrumentationResult> instrumentationResults) {
    this.originalConfiguration = originalConfiguration;
    this.incomingConfiguration = incomingConfiguration;
    this.instrumentationResults = instrumentationResults;

    Set<ProbeDefinition> originalDefinitions =
        this.originalConfiguration != null
            ? new HashSet<>(this.originalConfiguration.getDefinitions())
            : new HashSet<>();
    Set<ProbeDefinition> incomingDefinitions =
        new HashSet<>(this.incomingConfiguration.getDefinitions());

    addedDefinitions =
        incomingDefinitions.stream()
            .filter(it -> !originalDefinitions.contains(it))
            .collect(Collectors.toList());

    removedDefinitions =
        originalDefinitions.stream()
            .filter(it -> !incomingDefinitions.contains(it))
            .collect(Collectors.toList());

    filteredListChanged =
        !Objects.equals(
                originalConfiguration != null ? originalConfiguration.getAllowList() : null,
                incomingConfiguration.getAllowList())
            || !Objects.equals(
                originalConfiguration != null ? originalConfiguration.getDenyList() : null,
                incomingConfiguration.getDenyList());

    if (filteredListChanged) {
      changedBlockedTypes = findChangesInBlockedTypes();
    } else {
      changedBlockedTypes = Collections.emptyList();
    }
  }

  private Trie buildChangedClasses() {
    List<ProbeDefinition> changedDefinitions =
        Stream.concat(removedDefinitions.stream(), addedDefinitions.stream())
            .collect(Collectors.toList());
    Trie changedClasses = new Trie();
    for (ProbeDefinition definition : changedDefinitions) {
      InstrumentationResult instrumentationResult = instrumentationResults.get(definition.getId());
      String key = instrumentationResult != null ? instrumentationResult.getTypeName() : null;
      if (key == null || key.equals("")) {
        key = definition.getWhere().getTypeName();
        if (key == null || key.equals("")) {
          key = definition.getWhere().getSourceFile();
          if (key == null || key.equals("")) {
            continue;
          }
          // remove extension if any
          int idx = key.lastIndexOf('.');
          if (idx > -1) {
            key = key.substring(0, idx);
          }
          key = normalizeFilePath(key);
        }
      } else {
        key = normalizeFilePath(key);
      }
      LOGGER.debug(
          "instrumented class changed: {} for probe ids: {}",
          key,
          definition.getAllProbeIds().collect(Collectors.toList()));
      changedClasses.insert(reverseStr(key));
    }
    for (String typeName : changedBlockedTypes) {
      LOGGER.debug("blocked class found: {}", typeName);
      changedClasses.insert(reverseStr(typeName));
    }
    return changedClasses;
  }

  private String normalizeFilePath(String filePath) {
    filePath = filePath.replace('/', '.');
    return filePath;
  }

  public Collection<ProbeDefinition> getAddedDefinitions() {
    return addedDefinitions;
  }

  public Collection<ProbeDefinition> getRemovedDefinitions() {
    return removedDefinitions;
  }

  public boolean hasProbeRelatedChanges() {
    return !addedDefinitions.isEmpty() || !removedDefinitions.isEmpty() || filteredListChanged;
  }

  public boolean hasRateLimitRelatedChanged() {
    return originalConfiguration != null
            && originalConfiguration.getSampling() != incomingConfiguration.getSampling()
        || hasProbeRelatedChanges();
  }

  public boolean hasChangedClasses() {
    return !getAllChangedClasses().isEmpty();
  }

  Trie getAllChangedClasses() {
    if (allChangedClasses == null) {
      allChangedClasses = buildChangedClasses();
    }
    return allChangedClasses;
  }

  public List<Class<?>> getAllLoadedChangedClasses(Class<?>[] allLoadedClasses) {
    List<Class<?>> classesToBeTransformed = new ArrayList<>();
    Trie changedClasses = getAllChangedClasses();
    for (Class<?> clazz : allLoadedClasses) {
      // try first with FQN (java.lang.String)
      String typeName = clazz.getName();
      if (!changedClasses.contains(reverseStr(typeName))) {
        // fallback to matching on SimpleName (String)
        String simpleName = extractSimpleName(clazz);
        if (!changedClasses.contains(reverseStr(simpleName))) {
          // prefix match with FQN
          if (!changedClasses.containsPrefix(reverseStr(typeName))) {
            continue;
          }
        }
      }
      classesToBeTransformed.add(clazz);
    }
    return classesToBeTransformed;
  }

  List<String> findChangesInBlockedTypes() {
    AllowListHelper originalAllowListHelper =
        new AllowListHelper(originalConfiguration.getAllowList());
    DenyListHelper originalDenyListHelper = new DenyListHelper(originalConfiguration.getDenyList());

    AllowListHelper incommingAllowListHelper =
        new AllowListHelper(incomingConfiguration.getAllowList());
    DenyListHelper incommingDenyListHelper =
        new DenyListHelper(incomingConfiguration.getDenyList());

    List<String> changedTypes = new ArrayList();

    for (InstrumentationResult result : instrumentationResults.values()) {
      boolean originalAllowed = true;
      boolean incommingAllowed = true;
      String typeName = result.getTypeName();

      if (originalDenyListHelper.isDenied(typeName)) {
        LOGGER.debug("type {} was denied", typeName);
        originalAllowed = false;
      } else if (!originalAllowListHelper.isAllowAll()
          && !originalAllowListHelper.isAllowed(typeName)) {
        LOGGER.debug("type {} was not allowed", typeName);
        originalAllowed = false;
      }

      if (incommingDenyListHelper.isDenied(typeName)) {
        LOGGER.debug("type {} will be denied", typeName);
        incommingAllowed = false;
      } else if (!incommingAllowListHelper.isAllowAll()
          && !incommingAllowListHelper.isAllowed(typeName)) {
        LOGGER.debug("type {} will not be allowed", typeName);
        incommingAllowed = false;
      }

      if (incommingAllowed != originalAllowed) {
        changedTypes.add(typeName);
      }
    }

    return changedTypes;
  }

  @Override
  public String toString() {
    return "ConfigurationComparer{"
        + "addedDefinitions="
        + addedDefinitions.size()
        + ", removedDefinitions="
        + removedDefinitions.size()
        + ", filteredListChanged="
        + filteredListChanged
        + ", changedBlockedTypes="
        + changedBlockedTypes.size()
        + '}';
  }
}
