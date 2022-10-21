package datadog.trace.api.config;

import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_DEDUPLICATION_ENABLED;
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_ENABLED;
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_MAX_CONCURRENT_REQUESTS;
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_REQUEST_SAMPLING;
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_VULNERABILITIES_PER_REQUEST;
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS;
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_WEAK_HASH_ALGORITHMS;
import static datadog.trace.api.config.IastConfig.IAST_DEDUPLICATION_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_MAX_CONCURRENT_REQUESTS;
import static datadog.trace.api.config.IastConfig.IAST_REQUEST_SAMPLING;
import static datadog.trace.api.config.IastConfig.IAST_VULNERABILITIES_PER_REQUEST;
import static datadog.trace.api.config.IastConfig.IAST_WEAK_CIPHER_ALGORITHMS;
import static datadog.trace.api.config.IastConfig.IAST_WEAK_HASH_ALGORITHMS;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IastFeatureConfig extends AbstractFeatureConfig {
  private static final Logger LOGGER = LoggerFactory.getLogger(IastFeatureConfig.class);
  private final Set<String> iastWeakHashAlgorithms;
  private final Pattern iastWeakCipherAlgorithms;
  private final boolean iastDeduplicationEnabled;
  private final boolean iastEnabled;
  private final int iastMaxConcurrentRequests;
  private final int iastVulnerabilitiesPerRequest;
  private final float iastRequestSampling;

  public IastFeatureConfig(ConfigProvider configProvider) {
    super(configProvider);
    this.iastWeakHashAlgorithms =
        tryMakeImmutableSet(
            configProvider.getSet(IAST_WEAK_HASH_ALGORITHMS, DEFAULT_IAST_WEAK_HASH_ALGORITHMS));
    this.iastWeakCipherAlgorithms =
        getPattern(
            DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS,
            configProvider.getString(IAST_WEAK_CIPHER_ALGORITHMS));
    this.iastDeduplicationEnabled =
        configProvider.getBoolean(IAST_DEDUPLICATION_ENABLED, DEFAULT_IAST_DEDUPLICATION_ENABLED);
    this.iastEnabled = configProvider.getBoolean(IAST_ENABLED, DEFAULT_IAST_ENABLED);
    this.iastMaxConcurrentRequests =
        configProvider.getInteger(
            IAST_MAX_CONCURRENT_REQUESTS, DEFAULT_IAST_MAX_CONCURRENT_REQUESTS);
    this.iastVulnerabilitiesPerRequest =
        configProvider.getInteger(
            IAST_VULNERABILITIES_PER_REQUEST, DEFAULT_IAST_VULNERABILITIES_PER_REQUEST);
    this.iastRequestSampling =
        configProvider.getFloat(IAST_REQUEST_SAMPLING, DEFAULT_IAST_REQUEST_SAMPLING);
  }

  private static Pattern getPattern(String defaultValue, String userValue) {
    try {
      if (userValue != null) {
        return Pattern.compile(userValue);
      }
    } catch (Exception e) {
      LOGGER.debug("Cannot create pattern from user value {}", userValue);
    }
    return Pattern.compile(defaultValue);
  }

  public Set<String> getIastWeakHashAlgorithms() {
    return this.iastWeakHashAlgorithms;
  }

  public Pattern getIastWeakCipherAlgorithms() {
    return this.iastWeakCipherAlgorithms;
  }

  public boolean isIastDeduplicationEnabled() {
    return this.iastDeduplicationEnabled;
  }

  public boolean isIastEnabled() {
    return this.iastEnabled;
  }

  public int getIastMaxConcurrentRequests() {
    return this.iastMaxConcurrentRequests;
  }

  public int getIastVulnerabilitiesPerRequest() {
    return this.iastVulnerabilitiesPerRequest;
  }

  public float getIastRequestSampling() {
    return this.iastRequestSampling;
  }
}
