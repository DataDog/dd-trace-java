package com.datadog.debugger.agent;

import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.ProbeDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles comparison with current configuration and incoming new configuration Provides also list
 * of classes that need to be changed according to new definitions
 */
public class ConfigurationComparer {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationComparer.class);

  private final Configuration originalConfiguration;
  private final Configuration incomingConfiguration;

  private final List<ProbeDefinition> addedDefinitions;
  private final List<ProbeDefinition> removedDefinitions;
  private final boolean filteredListChanged;
  private final Map<String, InstrumentationResult> instrumentationResults;
  private final List<String> changedBlockedTypes;

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

  public Collection<ProbeDefinition> getAddedDefinitions() {
    return addedDefinitions;
  }

  public Collection<ProbeDefinition> getRemovedDefinitions() {
    return removedDefinitions;
  }

  public Map<String, InstrumentationResult> getInstrumentationResults() {
    return instrumentationResults;
  }

  public List<String> getChangedBlockedTypes() {
    return changedBlockedTypes;
  }

  public boolean hasProbeRelatedChanges() {
    return !addedDefinitions.isEmpty() || !removedDefinitions.isEmpty() || filteredListChanged;
  }

  public boolean hasRateLimitRelatedChanged() {
    return originalConfiguration != null
            && originalConfiguration.getSampling() != incomingConfiguration.getSampling()
        || hasProbeRelatedChanges();
  }

  List<String> findChangesInBlockedTypes() {
    AllowListHelper originalAllowListHelper =
        new AllowListHelper(originalConfiguration.getAllowList());
    DenyListHelper originalDenyListHelper = new DenyListHelper(originalConfiguration.getDenyList());

    AllowListHelper incomingAllowListHelper =
        new AllowListHelper(incomingConfiguration.getAllowList());
    DenyListHelper incomingDenyListHelper = new DenyListHelper(incomingConfiguration.getDenyList());

    List<String> changedTypes = new ArrayList<>();

    for (InstrumentationResult result : instrumentationResults.values()) {
      boolean originalAllowed = true;
      boolean incomingAllowed = true;
      String typeName = result.getTypeName();

      if (originalDenyListHelper.isDenied(typeName)) {
        LOGGER.debug("type {} was denied", typeName);
        originalAllowed = false;
      } else if (!originalAllowListHelper.isAllowAll()
          && !originalAllowListHelper.isAllowed(typeName)) {
        LOGGER.debug("type {} was not allowed", typeName);
        originalAllowed = false;
      }

      if (incomingDenyListHelper.isDenied(typeName)) {
        LOGGER.debug("type {} will be denied", typeName);
        incomingAllowed = false;
      } else if (!incomingAllowListHelper.isAllowAll()
          && !incomingAllowListHelper.isAllowed(typeName)) {
        LOGGER.debug("type {} will not be allowed", typeName);
        incomingAllowed = false;
      }

      if (incomingAllowed != originalAllowed) {
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
