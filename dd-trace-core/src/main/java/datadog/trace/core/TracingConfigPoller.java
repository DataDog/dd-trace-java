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
    private static final String CONFIG_OVERRIDES = "config_overrides";

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
      if (CONFIG_OVERRIDES.equals(configKey.getConfigId())) {

        ConfigOverrides overrides =
            CONFIG_OVERRIDES_ADAPTER.fromJson(
                Okio.buffer(Okio.source(new ByteArrayInputStream(content))));

        if (null != overrides) {
          applyConfigOverrides(overrides);
        } else {
          removeConfigOverrides();
        }
      }
    }

    @Override
    public void remove(ParsedConfigKey configKey, PollingRateHinter hinter) {
      if (CONFIG_OVERRIDES.equals(configKey.getConfigId())) {
        removeConfigOverrides();
      }
    }

    @Override
    public void commit(PollingRateHinter hinter) {}
  }

  void applyConfigOverrides(ConfigOverrides overrides) {
    DynamicConfig.Builder builder = dynamicConfig.initial();
    maybeOverride(builder::setServiceMapping, overrides.serviceMapping, SERVICE_MAPPING);
    maybeOverride(builder::setTaggedHeaders, overrides.taggedHeaders, HEADER_TAGS);
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
    @Json(name = "tracing_service_mapping")
    public Map<String, String> serviceMapping;

    @Json(name = "tracing_header_tags")
    public Map<String, String> taggedHeaders;
  }
}
