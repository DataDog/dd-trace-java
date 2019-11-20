package datadog.trace.common.util;

import com.timgroup.statsd.NoOpStatsDClient;
import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import datadog.opentracing.DDTraceOTInfo;
import datadog.trace.api.Config;
import lombok.Getter;

/** Helper class for configuring a StatsDClient used for health reporting. */
public final class HealthMetrics {
  public static final HealthMetrics DISABLED = new HealthMetrics();

  public static final String PREFIX = "datadog.tracer";

  public static final String LANG_TAG = "lang";
  public static final String LANG_VERSION_TAG = "lang_version";
  public static final String LANG_INTERPRETER_TAG = "lang_interpreter";
  public static final String LANG_INTERPRETER_VENDOR_TAG = "lang_interpreter_vendor";
  public static final String TRACER_VERSION_TAG = "tracer_version";

  @Getter private final boolean enabled;

  @Getter private final String hostInfo;

  @Getter private final StatsDClient statsDClient;

  private HealthMetrics(final String host, final int port) {
    enabled = true;
    hostInfo = host + ":" + port;
    statsDClient = new NonBlockingStatsDClient(PREFIX, host, port, getDefaultTags());
  }

  private HealthMetrics() {
    enabled = false;
    hostInfo = null;
    statsDClient = new NoOpStatsDClient();
  }

  @Override
  public final String toString() {
    if (statsDClient == null) {
      return "";
    } else {
      if (hostInfo != null) {
        return "StatsD";
      } else {
        return "StatsD { hostInfo=" + hostInfo + " }";
      }
    }
  }

  private static final String[] getDefaultTags() {
    return new String[] {
      tag(LANG_TAG, "java"),
      tag(LANG_VERSION_TAG, DDTraceOTInfo.JAVA_VERSION),
      tag(LANG_INTERPRETER_TAG, DDTraceOTInfo.JAVA_VM_NAME),
      tag(LANG_INTERPRETER_VENDOR_TAG, DDTraceOTInfo.JAVA_VM_VENDOR),
      tag(TRACER_VERSION_TAG, DDTraceOTInfo.VERSION)
    };
  }

  private static final String tag(final String tagPrefix, final String tagValue) {
    return tagPrefix + ":" + tagValue;
  }

  public static final class Builder {
    private String host = null;
    private Integer port = null;

    public final Builder withHost(final String host) {
      this.host = host;

      return this;
    }

    public final Builder withPort(final int port) {
      this.port = port;

      return this;
    }

    public final Builder fromConfig(final Config config) {
      if (config.isHealthMetricsEnabled()) {
        String host = config.getHealthMetricsStatsdHost();
        if (host == null) {
          host = config.getJmxFetchStatsdHost();
        }
        if (host == null) {
          host = config.getAgentHost();
        }
        this.host = host;

        Integer port = config.getHealthMetricsStatsdPort();
        if (port == null) {
          port = config.getJmxFetchStatsdPort();
        }
        this.port = port;
      } else {
        this.host = null;
        this.port = null;
      }
      return this;
    }

    public final HealthMetrics build() {
      if (host == null) {
        return HealthMetrics.DISABLED;
      } else {
        return new HealthMetrics(host, port);
      }
    }
  }
}
