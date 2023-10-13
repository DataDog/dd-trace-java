package com.datadog.appsec.config;

import com.datadog.appsec.config.CurrentAppSecConfig.DirtyStatus;
import java.util.AbstractList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

class CollectedUserConfigs extends AbstractList<AppSecUserConfig> {
  private final List<AppSecUserConfig> userConfigs = new LinkedList<>();

  public DirtyStatus addConfig(AppSecUserConfig newUserConfig) {
    if (newUserConfig == null) {
      throw new NullPointerException("user config was null");
    }
    DirtyStatus removedDirty = removeConfig(newUserConfig.configKey);
    // it would be more accurate to actually compare the contents of the
    // custom rules and the toggling instructions to see if anything changed
    DirtyStatus newDirty = newUserConfig.dirtyEffect();
    this.userConfigs.add(newUserConfig);

    Collections.sort(userConfigs, Comparator.comparing(c -> c.configKey));

    removedDirty.mergeFrom(newDirty);
    return removedDirty;
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
      return new DirtyStatus();
    }
    AppSecUserConfig removedElement = maybeRemovedElement.get();
    return removedElement.dirtyEffect();
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
