package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.DebuggerProductChangesListener.ConfigurationAcceptor.Source.REMOTE_CONFIG;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.remoteconfig.ConfigurationChangesListener;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.util.TagsHelper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebuggerProductChangesListener implements ProductListener {
  public static final int MAX_ALLOWED_METRIC_PROBES = 100;
  public static final int MAX_ALLOWED_LOG_PROBES = 100;
  public static final int MAX_ALLOWED_SPAN_PROBES = 100;
  public static final int MAX_ALLOWED_SPAN_DECORATION_PROBES = 100;
  private static final Logger LOGGER =
      LoggerFactory.getLogger(DebuggerProductChangesListener.class);

  public interface ConfigurationAcceptor {
    enum Source {
      REMOTE_CONFIG,
      EXCEPTION
    }

    void accept(Source source, Collection<? extends ProbeDefinition> definitions);
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
  private final Map<String, Consumer<DefinitionBuilder>> configChunks = new HashMap<>();

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
      configChunks.put(configId, definitions -> definitions.add(metricProbe));
    } else if (configId.startsWith("logProbe_")) {
      LogProbe logProbe = Adapter.deserializeLogProbe(content);
      configChunks.put(configId, definitions -> definitions.add(logProbe));
    } else if (configId.startsWith("spanProbe_")) {
      SpanProbe spanProbe = Adapter.deserializeSpanProbe(content);
      configChunks.put(configId, definitions -> definitions.add(spanProbe));
    } else if (configId.startsWith("spanDecorationProbe_")) {
      SpanDecorationProbe spanDecorationProbe = Adapter.deserializeSpanDecorationProbe(content);
      configChunks.put(configId, definitions -> definitions.add(spanDecorationProbe));
    } else if (IS_UUID.test(configId)) {
      Configuration newConfig = Adapter.deserializeConfiguration(content);
      if (newConfig.getService().equals(serviceName)) {
        configChunks.put(
            configId,
            (builder) -> {
              builder.addAll(newConfig.getDefinitions());
            });
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
  public void commit(ConfigurationChangesListener.PollingRateHinter pollingRateHinter) {
    DefinitionBuilder builder = new DefinitionBuilder();
    for (Consumer<DefinitionBuilder> chunk : configChunks.values()) {
      chunk.accept(builder);
    }
    configurationAcceptor.accept(REMOTE_CONFIG, builder.build());
  }

  static class DefinitionBuilder {
    private final Collection<ProbeDefinition> definitions = new ArrayList<>();
    private int metricProbeCount = 0;
    private int logProbeCount = 0;
    private int spanProbeCount = 0;
    private int spanDecorationProbeCount = 0;

    void add(MetricProbe probe) {
      if (metricProbeCount >= MAX_ALLOWED_METRIC_PROBES) {
        LOGGER.debug("Max allowed metric probes reached, ignoring new probe: {}", probe);
        return;
      }
      definitions.add(probe);
      metricProbeCount++;
    }

    void add(LogProbe probe) {
      if (logProbeCount >= MAX_ALLOWED_LOG_PROBES) {
        LOGGER.debug("Max allowed log probes reached, ignoring new probe: {}", probe);
        return;
      }
      definitions.add(probe);
      logProbeCount++;
    }

    void add(SpanProbe probe) {
      if (spanProbeCount >= MAX_ALLOWED_SPAN_PROBES) {
        LOGGER.debug("Max allowed span probes reached, ignoring new probe: {}", probe);
        return;
      }
      definitions.add(probe);
      spanProbeCount++;
    }

    void add(SpanDecorationProbe probe) {
      if (spanDecorationProbeCount >= MAX_ALLOWED_SPAN_DECORATION_PROBES) {
        LOGGER.debug("Max allowed span decoration probes reached, ignoring new probe: {}", probe);
        return;
      }
      definitions.add(probe);
      spanDecorationProbeCount++;
    }

    void addAll(Collection<ProbeDefinition> newDefinitions) {
      for (ProbeDefinition definition : newDefinitions) {
        if (definition instanceof MetricProbe) {
          add((MetricProbe) definition);
        } else if (definition instanceof LogProbe) {
          add((LogProbe) definition);
        } else if (definition instanceof SpanProbe) {
          add((SpanProbe) definition);
        } else if (definition instanceof SpanDecorationProbe) {
          add((SpanDecorationProbe) definition);
        }
      }
    }

    Collection<ProbeDefinition> build() {
      return definitions;
    }
  }
}
