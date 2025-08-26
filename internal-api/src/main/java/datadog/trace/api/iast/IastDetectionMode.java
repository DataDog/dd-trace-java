package datadog.trace.api.iast;

import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_DEDUPLICATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_MAX_CONCURRENT_REQUESTS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_MAX_RANGE_COUNT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_REQUEST_SAMPLING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_VULNERABILITIES_PER_REQUEST;
import static datadog.trace.api.config.IastConfig.IAST_DEDUPLICATION_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_MAX_CONCURRENT_REQUESTS;
import static datadog.trace.api.config.IastConfig.IAST_MAX_RANGE_COUNT;
import static datadog.trace.api.config.IastConfig.IAST_REQUEST_SAMPLING;
import static datadog.trace.api.config.IastConfig.IAST_VULNERABILITIES_PER_REQUEST;

import datadog.config.ConfigProvider;
import javax.annotation.Nonnull;

public enum IastDetectionMode {
  FULL {
    @Override
    public int getIastMaxConcurrentRequests(@Nonnull final ConfigProvider config) {
      return UNLIMITED;
    }

    @Override
    public int getIastVulnerabilitiesPerRequest(@Nonnull final ConfigProvider config) {
      return UNLIMITED;
    }

    @Override
    public float getIastRequestSampling(@Nonnull final ConfigProvider config) {
      return 100;
    }

    @Override
    public boolean isIastDeduplicationEnabled(@Nonnull final ConfigProvider config) {
      return false;
    }

    @Override
    public int getIastMaxRangeCount(@Nonnull final ConfigProvider config) {
      return Integer.MAX_VALUE;
    }
  },

  DEFAULT {
    @Override
    public int getIastMaxConcurrentRequests(@Nonnull final ConfigProvider config) {
      return config.getInteger(IAST_MAX_CONCURRENT_REQUESTS, DEFAULT_IAST_MAX_CONCURRENT_REQUESTS);
    }

    @Override
    public int getIastVulnerabilitiesPerRequest(@Nonnull final ConfigProvider config) {
      return config.getInteger(
          IAST_VULNERABILITIES_PER_REQUEST, DEFAULT_IAST_VULNERABILITIES_PER_REQUEST);
    }

    @Override
    public float getIastRequestSampling(@Nonnull final ConfigProvider config) {
      return config.getFloat(IAST_REQUEST_SAMPLING, DEFAULT_IAST_REQUEST_SAMPLING);
    }

    @Override
    public boolean isIastDeduplicationEnabled(@Nonnull final ConfigProvider config) {
      return config.getBoolean(IAST_DEDUPLICATION_ENABLED, DEFAULT_IAST_DEDUPLICATION_ENABLED);
    }

    @Override
    public int getIastMaxRangeCount(@Nonnull final ConfigProvider config) {
      return config.getInteger(IAST_MAX_RANGE_COUNT, DEFAULT_IAST_MAX_RANGE_COUNT);
    }
  };

  public static final int UNLIMITED = Integer.MIN_VALUE;

  public abstract int getIastMaxConcurrentRequests(@Nonnull ConfigProvider config);

  public abstract int getIastVulnerabilitiesPerRequest(@Nonnull ConfigProvider config);

  public abstract float getIastRequestSampling(@Nonnull ConfigProvider config);

  public abstract boolean isIastDeduplicationEnabled(@Nonnull ConfigProvider config);

  public abstract int getIastMaxRangeCount(@Nonnull ConfigProvider config);
}
