package com.datadog.appsec.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MergedAsmFeatures {

  private final Map<String, AppSecFeatures> configs = new ConcurrentHashMap<>();
  private volatile AppSecFeatures mergedData;

  public void addConfig(final String cfgKey, final AppSecFeatures features) {
    this.configs.put(cfgKey, features);
    this.mergedData = null;
  }

  public void removeConfig(final String cfgKey) {
    this.configs.remove(cfgKey);
    this.mergedData = null;
  }

  public AppSecFeatures getMergedData() {
    if (mergedData == null) {
      synchronized (this) {
        if (mergedData == null) {
          mergedData = configs.values().stream().reduce(new AppSecFeatures(), this::merge);
        }
      }
    }
    return this.mergedData;
  }

  private AppSecFeatures merge(final AppSecFeatures target, final AppSecFeatures newFeatures) {
    mergeAsm(target, newFeatures.asm);
    mergeAutoUserInstrum(target, newFeatures.autoUserInstrum);
    return target;
  }

  private void mergeAsm(final AppSecFeatures target, final AppSecFeatures.Asm newValue) {
    if (newValue == null || newValue.enabled == null) {
      return;
    }
    target.asm = newValue;
  }

  private void mergeAutoUserInstrum(
      final AppSecFeatures target, final AppSecFeatures.AutoUserInstrum newValue) {
    if (newValue == null || newValue.mode == null) {
      return;
    }
    target.autoUserInstrum = newValue;
  }
}
