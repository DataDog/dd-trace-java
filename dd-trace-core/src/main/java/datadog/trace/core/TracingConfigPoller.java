package datadog.trace.core;

import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_APM_CUSTOM_TAGS;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_APM_HTTP_HEADER_TAGS;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_APM_LOGS_INJECTION;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_APM_TRACING_DATA_STREAMS_ENABLED;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_APM_TRACING_SAMPLE_RATE;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationChangesListener.PollingRateHinter;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.DynamicConfig;
import datadog.trace.logging.GlobalLogLevelSwitcher;
import datadog.trace.logging.LogLevel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TracingConfigPoller {
  private static final Logger log = LoggerFactory.getLogger(TracingConfigPoller.class);

  private final DynamicConfig<?> dynamicConfig;

  private boolean startupLogsEnabled;

  private Runnable stopPolling;

  public TracingConfigPoller(DynamicConfig<?> dynamicConfig) {
    this.dynamicConfig = dynamicConfig;
  }

  public void start(Config config, SharedCommunicationObjects sco) {
    this.startupLogsEnabled = config.isStartupLogsEnabled();
    ConfigurationPoller ConfigPoller = sco.configurationPoller(config);
    // TODO: Add CAPABILITY_APM_TRACING_TRACING_ENABLED flag when tracing_enabled is implemented for
    // Remote config
    ConfigPoller.addCapabilities(
        CAPABILITY_APM_TRACING_SAMPLE_RATE
            | CAPABILITY_APM_LOGS_INJECTION
            | CAPABILITY_APM_HTTP_HEADER_TAGS
            | CAPABILITY_APM_CUSTOM_TAGS
            | CAPABILITY_APM_TRACING_DATA_STREAMS_ENABLED);
    stopPolling = new Updater().register(config, ConfigPoller);
  }

  public void stop() {
    if (null != stopPolling) {
      stopPolling.run();
    }
  }

  final class Updater implements ProductListener {
    private final JsonAdapter<ConfigOverrides> CONFIG_OVERRIDES_ADAPTER;

    {
      Moshi MOSHI = new Moshi.Builder().build();
      CONFIG_OVERRIDES_ADAPTER = MOSHI.adapter(ConfigOverrides.class);
    }

    private boolean receivedOverrides = false;

    public Runnable register(Config config, ConfigurationPoller poller) {
      if (null != poller) {
        poller.addListener(Product.APM_TRACING, this);
        return poller::stop;
      } else {
        return null;
      }
    }

    @Override
    public void accept(ParsedConfigKey configKey, byte[] content, PollingRateHinter hinter)
        throws IOException {

      ConfigOverrides overrides =
          CONFIG_OVERRIDES_ADAPTER.fromJson(
              Okio.buffer(Okio.source(new ByteArrayInputStream(content))));

      if (null != overrides && null != overrides.libConfig) {
        receivedOverrides = true;
        applyConfigOverrides(overrides.libConfig);
        if (log.isDebugEnabled()) {
          log.debug(
              "Applied APM_TRACING overrides: {}", CONFIG_OVERRIDES_ADAPTER.toJson(overrides));
        }
      } else {
        log.debug("No APM_TRACING overrides");
      }
    }

    @Override
    public void remove(ParsedConfigKey configKey, PollingRateHinter hinter) {}

    @Override
    public void commit(PollingRateHinter hinter) {
      if (!receivedOverrides) {
        removeConfigOverrides();
        log.debug("Removed APM_TRACING overrides");
      } else {
        receivedOverrides = false;
      }
    }
  }

  void applyConfigOverrides(LibConfig libConfig) {
    DynamicConfig<?>.Builder builder = dynamicConfig.initial();

    if (libConfig.debugEnabled != null) {
      if (Boolean.TRUE.equals(libConfig.debugEnabled)) {
        GlobalLogLevelSwitcher.get().switchLevel(LogLevel.DEBUG);
      } else {
        // Disable debugEnabled when it was set to true at startup
        // The default log level when debugEnabled=false depends on the STARTUP_LOGS_ENABLED flag
        // See datadog.trace.bootstrap.Agent.configureLogger()
        if (startupLogsEnabled) {
          GlobalLogLevelSwitcher.get().switchLevel(LogLevel.INFO);
        } else {
          GlobalLogLevelSwitcher.get().switchLevel(LogLevel.WARN);
        }
      }
    } else {
      GlobalLogLevelSwitcher.get().restore();
    }

    maybeOverride(builder::setRuntimeMetricsEnabled, libConfig.runtimeMetricsEnabled);
    maybeOverride(builder::setLogsInjectionEnabled, libConfig.logsInjectionEnabled);
    maybeOverride(builder::setDataStreamsEnabled, libConfig.dataStreamsEnabled);

    maybeOverride(builder::setServiceMapping, libConfig.serviceMapping);
    maybeOverride(builder::setHeaderTags, libConfig.headerTags);

    maybeOverride(builder::setTraceSampleRate, libConfig.traceSampleRate);
    maybeOverride(builder::setTracingTags, parseTagListToMap(libConfig.tracingTags));
    builder.apply();
  }

  void removeConfigOverrides() {
    dynamicConfig.resetTraceConfig();
    GlobalLogLevelSwitcher.get().restore();
  }

  private <T> void maybeOverride(Consumer<T> setter, T override) {
    if (null != override) {
      setter.accept(override);
    }
  }

  private Map<String, String> parseTagListToMap(List<String> input) {
    Map<String, String> resultMap = Collections.emptyMap();
    if (null != input && !input.isEmpty()) {
      resultMap = new HashMap<>();
      for (String s : input) {
        String[] keyValue = s.split(":");
        if (keyValue.length == 2) {
          String key = keyValue[0].trim();
          String value = keyValue[1].trim();
          resultMap.put(key, value);
        }
      }
    }
    return resultMap;
  }

  static final class ConfigOverrides {
    @Json(name = "lib_config")
    public LibConfig libConfig;
  }

  static final class LibConfig {
    @Json(name = "tracing_debug")
    public Boolean debugEnabled;

    @Json(name = "runtime_metrics_enabled")
    public Boolean runtimeMetricsEnabled;

    @Json(name = "log_injection_enabled")
    public Boolean logsInjectionEnabled;

    @Json(name = "data_streams_enabled")
    public Boolean dataStreamsEnabled;

    @Json(name = "tracing_service_mapping")
    public List<ServiceMappingEntry> serviceMapping;

    @Json(name = "tracing_header_tags")
    public List<HeaderTagEntry> headerTags;

    @Json(name = "tracing_sampling_rate")
    public Double traceSampleRate;

    @Json(name = "tracing_tags")
    public List<String> tracingTags;
  }

  static final class ServiceMappingEntry implements Map.Entry<String, String> {
    @Json(name = "from_key")
    public String fromKey;

    @Json(name = "to_name")
    public String toName;

    @Override
    public String getKey() {
      return fromKey;
    }

    @Override
    public String getValue() {
      return toName;
    }

    @Override
    public String setValue(String value) {
      throw new UnsupportedOperationException();
    }
  }

  static final class HeaderTagEntry implements Map.Entry<String, String> {
    @Json(name = "header")
    public String header;

    @Json(name = "tag_name")
    public String tagName;

    @Override
    public String getKey() {
      return header;
    }

    @Override
    public String getValue() {
      return tagName;
    }

    @Override
    public String setValue(String value) {
      throw new UnsupportedOperationException();
    }
  }
}
