package datadog.trace.core;

import com.timgroup.statsd.NoOpStatsDClient;
import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import dagger.Module;
import dagger.Provides;
import datadog.trace.api.Config;
import javax.inject.Singleton;

@Module
class StatsDModule {
  public static final String LANG_STATSD_TAG = "lang";
  public static final String LANG_VERSION_STATSD_TAG = "lang_version";
  public static final String LANG_INTERPRETER_STATSD_TAG = "lang_interpreter";
  public static final String LANG_INTERPRETER_VENDOR_STATSD_TAG = "lang_interpreter_vendor";
  public static final String TRACER_VERSION_STATSD_TAG = "tracer_version";

  private final StatsDClient client;

  public StatsDModule() {
    this(null);
  }

  public StatsDModule(final StatsDClient client) {
    this.client = client;
  }

  @Singleton
  @Provides
  StatsDClient statsD(final Config config) {
    if (client != null) {
      return client;
    }
    return fromConfig(config);
  }

  public static StatsDClient fromConfig(final Config config) {
    if (!config.isHealthMetricsEnabled()) {
      return new NoOpStatsDClient();
    } else {
      String host = config.getHealthMetricsStatsdHost();
      if (host == null) {
        host = config.getJmxFetchStatsdHost();
      }
      if (host == null) {
        host = config.getAgentHost();
      }

      Integer port = config.getHealthMetricsStatsdPort();
      if (port == null) {
        port = config.getJmxFetchStatsdPort();
      }

      final String[] constantTags =
          new String[] {
            statsdTag(LANG_STATSD_TAG, "java"),
            statsdTag(LANG_VERSION_STATSD_TAG, DDTraceCoreInfo.JAVA_VERSION),
            statsdTag(LANG_INTERPRETER_STATSD_TAG, DDTraceCoreInfo.JAVA_VM_NAME),
            statsdTag(LANG_INTERPRETER_VENDOR_STATSD_TAG, DDTraceCoreInfo.JAVA_VM_VENDOR),
            statsdTag(TRACER_VERSION_STATSD_TAG, DDTraceCoreInfo.VERSION)
          };

      return new NonBlockingStatsDClient("datadog.tracer", host, port, constantTags);
    }
  }

  private static String statsdTag(final String tagPrefix, final String tagValue) {
    return tagPrefix + ":" + tagValue;
  }
}
