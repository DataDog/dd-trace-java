package com.datadog.debugger.agent;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.util.TagsHelper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebuggerProductChangesListener implements ProductListener {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(DebuggerProductChangesListener.class);

  public interface ConfigurationAcceptor {
    void accept(Configuration configuration);
  }

  interface ConfigChunkBuilder {
    void buildWith(Configuration.Builder builder);
  }

  static class Adapter {
    static final JsonAdapter<Configuration> CONFIGURATION_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(Configuration.class);

    static final JsonAdapter<MetricProbe> METRIC_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(MetricProbe.class);

    static final JsonAdapter<LogProbe> LOG_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(LogProbe.class);

    static final JsonAdapter<SpanProbe> SPAN_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(SpanProbe.class);

    static final JsonAdapter<SpanDecorationProbe> SPAN_DECORATION_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(SpanDecorationProbe.class);

    static Configuration deserializeConfiguration(byte[] content) throws IOException {
      return deserialize(CONFIGURATION_JSON_ADAPTER, content);
    }

    static MetricProbe deserializeMetricProbe(byte[] content) throws IOException {
      return deserialize(METRIC_PROBE_JSON_ADAPTER, content);
    }

    static LogProbe deserializeLogProbe(byte[] content) throws IOException {
      return deserialize(LOG_PROBE_JSON_ADAPTER, content);
    }

    static SpanProbe deserializeSpanProbe(byte[] content) throws IOException {
      return deserialize(SPAN_PROBE_JSON_ADAPTER, content);
    }

    static SpanDecorationProbe deserializeSpanDecorationProbe(byte[] content) throws IOException {
      return deserialize(SPAN_DECORATION_PROBE_JSON_ADAPTER, content);
    }

    private static <T> T deserialize(JsonAdapter<T> adapter, byte[] content) throws IOException {
      return adapter.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
    }
  }

  private static final Predicate<String> IS_UUID =
      Pattern.compile(
              "^[0-9a-fA-F]{8}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{12}$")
          .asPredicate();

  private final String serviceName;
  private final ConfigurationAcceptor configurationAcceptor;
  private final Map<String, ConfigChunkBuilder> configChunks = new HashMap<>();

  DebuggerProductChangesListener(Config config, ConfigurationAcceptor configurationAcceptor) {
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.configurationAcceptor = configurationAcceptor;
  }

  @Override
  public void accept(
      ParsedConfigKey configKey,
      byte[] content,
      datadog.remoteconfig.ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException {
    String configId = configKey.getConfigId();
    if (configId.startsWith("metricProbe_")) {
      MetricProbe metricProbe = Adapter.deserializeMetricProbe(content);
      configChunks.put(configId, (builder) -> builder.add(metricProbe));
    } else if (configId.startsWith("logProbe_")) {
      LogProbe logProbe = Adapter.deserializeLogProbe(content);
      configChunks.put(configId, (builder) -> builder.add(logProbe.copy()));
    } else if (configId.startsWith("spanProbe_")) {
      SpanProbe spanProbe = Adapter.deserializeSpanProbe(content);
      configChunks.put(configId, (builder) -> builder.add(spanProbe));
    } else if (configId.startsWith("spanDecorationProbe_")) {
      SpanDecorationProbe spanDecorationProbe = Adapter.deserializeSpanDecorationProbe(content);
      configChunks.put(configId, (builder) -> builder.add(spanDecorationProbe));
    } else if (IS_UUID.test(configId)) {
      Configuration newConfig = Adapter.deserializeConfiguration(content);
      if (newConfig.getService().equals(serviceName)) {
        configChunks.put(configId, (builder) -> builder.add(newConfig));
      } else {
        throw new IOException(
            "got config.serviceName = " + newConfig.getService() + ", ignoring configuration");
      }
    } else {
      LOGGER.debug("Unsupported configuration id: {}, ignoring configuration", configId);
    }
  }

  @Override
  public void remove(
      ParsedConfigKey configKey,
      datadog.remoteconfig.ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException {
    configChunks.remove(configKey.getConfigId());
  }

  @Override
  public void commit(
      datadog.remoteconfig.ConfigurationChangesListener.PollingRateHinter pollingRateHinter) {

    Configuration.Builder builder = Configuration.builder().setService(serviceName);

    for (ConfigChunkBuilder chunk : configChunks.values()) {
      chunk.buildWith(builder);
    }

    Configuration config = builder.build();

    configurationAcceptor.accept(config);
  }
}
