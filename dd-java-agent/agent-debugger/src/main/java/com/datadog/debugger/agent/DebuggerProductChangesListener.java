package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.REMOTE_CONFIG;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.TriggerProbe;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.state.ConfigKey;
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
  public static final String LOG_PROBE_PREFIX = "logProbe_";
  public static final String METRIC_PROBE_PREFIX = "metricProbe_";
  public static final String SPAN_PROBE_PREFIX = "spanProbe_";
  public static final String TRIGGER_PROBE_PREFIX = "triggerProbe_";
  public static final String SPAN_DECORATION_PROBE_PREFIX = "spanDecorationProbe_";
  private static final Logger LOGGER =
      LoggerFactory.getLogger(DebuggerProductChangesListener.class);

  static class Adapter {
    static final JsonAdapter<Configuration> CONFIGURATION_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(Configuration.class);

    static final JsonAdapter<MetricProbe> METRIC_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(MetricProbe.class);

    static final JsonAdapter<LogProbe> LOG_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(LogProbe.class);

    static final JsonAdapter<SpanProbe> SPAN_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(SpanProbe.class);

    static final JsonAdapter<TriggerProbe> TRIGGER_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(TriggerProbe.class);

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

    static TriggerProbe deserializeTriggerProbe(byte[] content) throws IOException {
      return deserialize(TRIGGER_PROBE_JSON_ADAPTER, content);
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
              "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
          .asPredicate();

  private final String serviceName;
  private final ConfigurationAcceptor configurationAcceptor;
  private final Map<String, Consumer<DefinitionBuilder>> configChunks = new HashMap<>();

  DebuggerProductChangesListener(Config config, ConfigurationAcceptor configurationAcceptor) {
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.configurationAcceptor = configurationAcceptor;
  }

  @Override
  public void accept(ConfigKey configKey, byte[] content, PollingRateHinter pollingRateHinter)
      throws IOException {
    String configId = configKey.getConfigId();
    try {
      if (configId.startsWith(METRIC_PROBE_PREFIX)) {
        MetricProbe metricProbe = Adapter.deserializeMetricProbe(content);
        configChunks.put(configId, definitions -> definitions.add(metricProbe));
      } else if (configId.startsWith(LOG_PROBE_PREFIX)) {
        LogProbe logProbe = Adapter.deserializeLogProbe(content);
        configChunks.put(configId, definitions -> definitions.add(logProbe));
      } else if (configId.startsWith(SPAN_PROBE_PREFIX)) {
        SpanProbe spanProbe = Adapter.deserializeSpanProbe(content);
        configChunks.put(configId, definitions -> definitions.add(spanProbe));
      } else if (configId.startsWith(TRIGGER_PROBE_PREFIX)) {
        TriggerProbe triggerProbe = Adapter.deserializeTriggerProbe(content);
        configChunks.put(configId, definitions -> definitions.add(triggerProbe));
      } else if (configId.startsWith(SPAN_DECORATION_PROBE_PREFIX)) {
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
    } catch (Exception ex) {
      configurationAcceptor.handleException(configId, ex);
      throw new IOException(ex);
    }
  }

  @Override
  public void remove(ConfigKey configKey, PollingRateHinter pollingRateHinter) throws IOException {
    configChunks.remove(configKey.getConfigId());
  }

  @Override
  public void commit(PollingRateHinter pollingRateHinter) {
    DefinitionBuilder builder = new DefinitionBuilder();
    for (Consumer<DefinitionBuilder> chunk : configChunks.values()) {
      chunk.accept(builder);
    }
    configurationAcceptor.accept(REMOTE_CONFIG, builder.build());
  }

  static class DefinitionBuilder {
    private final Collection<ProbeDefinition> definitions = new ArrayList<>();
    private int triggerProbeCount = 0;
    private int metricProbeCount = 0;
    private int logProbeCount = 0;
    private int spanProbeCount = 0;
    private int spanDecorationProbeCount = 0;

    void add(MetricProbe probe) {
      definitions.add(probe);
      metricProbeCount++;
    }

    void add(LogProbe probe) {
      definitions.add(probe);
      logProbeCount++;
    }

    void add(SpanProbe probe) {
      definitions.add(probe);
      spanProbeCount++;
    }

    void add(TriggerProbe probe) {
      definitions.add(probe);
      triggerProbeCount++;
    }

    void add(SpanDecorationProbe probe) {
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
        } else if (definition instanceof TriggerProbe) {
          add((TriggerProbe) definition);
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
