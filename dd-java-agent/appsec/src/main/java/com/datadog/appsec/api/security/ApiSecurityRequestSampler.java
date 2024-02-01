package com.datadog.appsec.api.security;

import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_API_SECURITY_SAMPLE_RATE;

import com.datadog.appsec.config.AppSecFeaturesDeserializer;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApiSecurityRequestSampler {

  private static final Logger log = LoggerFactory.getLogger(ApiSecurityRequestSampler.class);

  private volatile int sampling;
  private final AtomicLong cumulativeCounter = new AtomicLong();

  public ApiSecurityRequestSampler(final Config config) {
    sampling = computeSamplingParameter(config.getApiSecurityRequestSampleRate());
  }

  public ApiSecurityRequestSampler(final Config config, ConfigurationPoller configurationPoller) {
    this(config);
    if (configurationPoller == null) {
      return;
    }

    configurationPoller.addListener(
        Product.ASM_FEATURES,
        "asm_api_security",
        AppSecFeaturesDeserializer.INSTANCE,
        (configKey, newConfig, pollingRateHinter) -> {
          if (newConfig != null && newConfig.apiSecurity != null) {
            Float newSamplingFloat = newConfig.apiSecurity.requestSampleRate;
            if (newSamplingFloat != null) {
              int newSampling = computeSamplingParameter(newSamplingFloat);
              if (newSampling != sampling) {
                sampling = newSampling;
                cumulativeCounter.set(0); // Reset current sampling counter
                if (sampling == 0) {
                  log.info("Api Security is disabled via remote-config");
                } else {
                  log.info(
                      "Api Security changed via remote-config. New sampling rate is {}% of all requests.",
                      sampling);
                }
              }
            }
          }
        });
    configurationPoller.addCapabilities(CAPABILITY_ASM_API_SECURITY_SAMPLE_RATE);
  }

  public boolean sampleRequest() {
    long prevValue = cumulativeCounter.getAndAdd(sampling);
    long newValue = prevValue + sampling;
    if (newValue / 100 == prevValue / 100 + 1) {
      // Sample request
      return true;
    }
    // Skipped by sampling
    return false;
  }

  static int computeSamplingParameter(final float pct) {
    if (pct >= 1) {
      return 100;
    }
    if (pct < 0) {
      // We don't support disabling Api Security by setting it, so we set it to 100%.
      // TODO: We probably want a warning here.
      return 100;
    }
    return (int) (pct * 100);
  }
}
