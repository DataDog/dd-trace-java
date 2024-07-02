package com.datadog.appsec.config;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.UserIdCollectionMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MergedAsmFeatures {

  private static final Logger LOGGER = LoggerFactory.getLogger(MergedAsmFeatures.class);

  private final Map<String, AppSecFeatures> configs = new ConcurrentHashMap<>();
  final AppSecFeatures.Asm localAsm;
  final AppSecFeatures.ApiSecurity localApiSecurity;
  final AppSecFeatures.AutoUserInstrum localAutoUserInstrum;
  private volatile AppSecFeatures mergedData;

  public MergedAsmFeatures(final Config tracerConfig) {
    localAsm = new AppSecFeatures.Asm();
    localAsm.enabled = tracerConfig.getAppSecActivation() == ProductActivation.FULLY_ENABLED;
    localApiSecurity = new AppSecFeatures.ApiSecurity();
    localApiSecurity.requestSampleRate = tracerConfig.getApiSecurityRequestSampleRate();
    localAutoUserInstrum = new AppSecFeatures.AutoUserInstrum();
    UserIdCollectionMode mode = tracerConfig.getAppSecUserIdCollectionMode();
    localAutoUserInstrum.mode = mode == null ? null : mode.toString();
  }

  public AppSecFeatures getMergedData() {
    if (mergedData == null) {
      synchronized (this) {
        if (mergedData == null) {
          final AppSecFeatures features =
              configs.values().stream().reduce(new AppSecFeatures(), this::merge);
          if (features.asm == null) {
            features.asm = localAsm;
          }
          if (features.apiSecurity == null) {
            features.apiSecurity = localApiSecurity;
          }
          if (features.autoUserInstrum == null) {
            features.autoUserInstrum = localAutoUserInstrum;
          }
          mergedData = features;
        }
      }
    }
    return this.mergedData;
  }

  public void addConfig(final String cfgKey, final AppSecFeatures features) {
    this.configs.put(cfgKey, features);
    this.mergedData = null;
  }

  public void removeConfig(final String cfgKey) {
    this.configs.remove(cfgKey);
    this.mergedData = null;
  }

  private AppSecFeatures merge(final AppSecFeatures target, final AppSecFeatures newFeatures) {
    mergeAsm(target, newFeatures.asm);
    mergeApiSecurity(target, newFeatures.apiSecurity);
    mergeAutoUserInstrum(target, newFeatures.autoUserInstrum);
    return target;
  }

  private void mergeAsm(final AppSecFeatures target, final AppSecFeatures.Asm newValue) {
    if (target.asm == localAsm) {
      return; // we are in a conflict state
    }
    if (newValue == null || newValue.enabled == null) {
      return;
    }
    final boolean enabled = newValue.enabled;
    if (target.asm != null && enabled != target.asm.enabled) {
      target.asm = localAsm;
      LOGGER.debug(
          SEND_TELEMETRY,
          "Conflict found applying activation {} != {}, in configs {}",
          target.asm.enabled,
          enabled,
          configs.keySet());
    } else {
      target.asm = newValue;
    }
  }

  private void mergeApiSecurity(
      final AppSecFeatures target, final AppSecFeatures.ApiSecurity newValue) {
    if (target.apiSecurity == localApiSecurity) {
      return; // we are in a conflict state
    }
    if (newValue == null || newValue.requestSampleRate == null) {
      return;
    }
    final float sampleRate = newValue.requestSampleRate;
    if (target.apiSecurity != null && sampleRate != target.apiSecurity.requestSampleRate) {
      target.apiSecurity = localApiSecurity;
      LOGGER.debug(
          SEND_TELEMETRY,
          "Conflict found applying api security sampling {} != {}, in configs {}",
          target.apiSecurity.requestSampleRate,
          sampleRate,
          configs.keySet());
    } else {
      target.apiSecurity = newValue;
    }
  }

  private void mergeAutoUserInstrum(
      final AppSecFeatures target, final AppSecFeatures.AutoUserInstrum newValue) {
    if (target.autoUserInstrum == localAutoUserInstrum) {
      return; // we are in a conflict state
    }
    if (newValue == null || newValue.mode == null) {
      return;
    }
    final String mode = newValue.mode;
    if (target.autoUserInstrum != null && !mode.equals(target.autoUserInstrum.mode)) {
      target.autoUserInstrum = localAutoUserInstrum;
      LOGGER.debug(
          SEND_TELEMETRY,
          "Conflict found applying auto user instrum {} != {}, in configs {}",
          target.autoUserInstrum.mode,
          mode,
          configs.keySet());
    } else {
      target.autoUserInstrum = newValue;
    }
  }
}
