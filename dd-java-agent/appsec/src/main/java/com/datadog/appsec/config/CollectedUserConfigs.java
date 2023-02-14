package com.datadog.appsec.config;

import java.util.AbstractList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

class CollectedUserConfigs extends AbstractList<AppSecUserConfig> {
  private final List<AppSecUserConfig> userConfigs = new LinkedList<>();

  public enum DirtyStatus {
    NOT_DIRTY(false, false),
    TOGGLING_DIRTY(true, false),
    RULES_DIRTY(false, true),
    BOTH_DIRTY(true, true);

    public final boolean toggling;
    public final boolean rules;

    DirtyStatus(boolean toggling, boolean rules) {
      this.toggling = toggling;
      this.rules = rules;
    }

    static DirtyStatus forCombination(boolean toggling, boolean rules) {
      if (!toggling && !rules) {
        return NOT_DIRTY;
      }
      if (toggling && rules) {
        return BOTH_DIRTY;
      }
      if (toggling) {
        return TOGGLING_DIRTY;
      }
      return RULES_DIRTY;
    }

    DirtyStatus merge(DirtyStatus other) {
      return forCombination(this.toggling || other.toggling, this.rules || other.rules);
    }
  }

  public DirtyStatus addConfig(AppSecUserConfig newUserConfig) {
    if (newUserConfig == null) {
      throw new NullPointerException("user config was null");
    }
    DirtyStatus removedDirty = removeConfig(newUserConfig.configKey);
    // it would be more accurate to actually compare the contents of the
    // custom rules and the toggling instructions to see if anything changed
    DirtyStatus newDirty =
        DirtyStatus.forCombination(
            newUserConfig.includesTogglingChanges(), newUserConfig.includesContextChanges());
    this.userConfigs.add(newUserConfig);

    Collections.sort(userConfigs, Comparator.comparing(c -> c.configKey));

    return removedDirty.merge(newDirty);
  }

  public DirtyStatus removeConfig(String cfgKey) {
    Optional<AppSecUserConfig> maybeRemovedElement =
        userConfigs.stream()
            .filter(cfg -> cfg.configKey.equals(cfgKey))
            .findAny()
            .map(
                cfg -> {
                  userConfigs.remove(cfg);
                  return cfg;
                });
    if (!maybeRemovedElement.isPresent()) {
      return DirtyStatus.NOT_DIRTY;
    }
    AppSecUserConfig removedElement = maybeRemovedElement.get();

    return DirtyStatus.forCombination(
        removedElement.includesTogglingChanges(), removedElement.includesContextChanges());
  }

  @Override
  public AppSecUserConfig get(int index) {
    return userConfigs.get(index);
  }

  @Override
  public int size() {
    return userConfigs.size();
  }
}
