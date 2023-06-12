package datadog.trace.core;

import static datadog.trace.api.config.TracerConfig.HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING;

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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TracingConfigPoller {
  private static final Logger log = LoggerFactory.getLogger(TracingConfigPoller.class);

  private final DynamicConfig dynamicConfig;

  private Runnable stopPolling;

  public TracingConfigPoller(DynamicConfig dynamicConfig) {
    this.dynamicConfig = dynamicConfig;
  }

  public void start(Config config, SharedCommunicationObjects sco) {
    stopPolling = new Updater().register(config, sco);
  }

  public void stop() {
    if (null != stopPolling) {
      stopPolling.run();
    }
  }

  final class Updater implements ProductListener {
    private final Moshi MOSHI = new Moshi.Builder().build();

    private final JsonAdapter<ConfigOverrides> CONFIG_OVERRIDES_ADAPTER =
        MOSHI.adapter(ConfigOverrides.class);

    public Runnable register(Config config, SharedCommunicationObjects sco) {
      ConfigurationPoller poller = sco.configurationPoller(config);
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
        applyConfigOverrides(overrides.libConfig);
      } else {
        removeConfigOverrides();
      }
    }

    @Override
    public void remove(ParsedConfigKey configKey, PollingRateHinter hinter) {
      removeConfigOverrides();
    }

    @Override
    public void commit(PollingRateHinter hinter) {}
  }

  void applyConfigOverrides(LibConfig libConfig) {
    DynamicConfig.Builder builder = dynamicConfig.initial();
    maybeOverride(builder::setServiceMapping, libConfig.serviceMapping, SERVICE_MAPPING);
    maybeOverride(builder::setHeaderTags, libConfig.headerTags, HEADER_TAGS);
    builder.apply();
    log.debug("Applied APM_TRACING overrides");
  }

  void removeConfigOverrides() {
    dynamicConfig.resetTraceConfig();
    log.debug("Removed APM_TRACING overrides");
  }

  private <T> void maybeOverride(Consumer<T> setter, T override, String key) {
    if (null != override) {
      setter.accept(override);
      log.debug("Overriding dd.{} with {}", key, override);
    }
  }

  static final class ConfigOverrides {
    @Json(name = "lib_config")
    public LibConfig libConfig;
  }

  static final class LibConfig {
    @Json(name = "tracing_service_mapping")
    public List<ServiceMappingEntry> serviceMapping;

    @Json(name = "tracing_header_tags")
    public List<HeaderTagEntry> headerTags;
  }

  static final class ServiceMappingEntry implements Map.Entry<String, String> {
    @Json(name = "from_name")
    public String fromName;

    @Json(name = "to_name")
    public String toName;

    @Override
    public String getKey() {
      return fromName;
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
