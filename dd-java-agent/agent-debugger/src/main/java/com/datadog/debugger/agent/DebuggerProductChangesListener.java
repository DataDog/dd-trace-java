package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.REMOTE_CONFIG;
import static com.datadog.debugger.probe.ProbeDefinitionDeserializer.deserializeConfiguration;
import static com.datadog.debugger.probe.ProbeDefinitionDeserializer.deserializeLogProbe;
import static com.datadog.debugger.probe.ProbeDefinitionDeserializer.deserializeMetricProbe;
import static com.datadog.debugger.probe.ProbeDefinitionDeserializer.deserializeSpanDecorationProbe;
import static com.datadog.debugger.probe.ProbeDefinitionDeserializer.deserializeSpanProbe;
import static com.datadog.debugger.probe.ProbeDefinitionDeserializer.deserializeTriggerProbe;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.TriggerProbe;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.util.TagsHelper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
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
        MetricProbe metricProbe = deserializeMetricProbe(content);
        configChunks.put(configId, definitions -> definitions.add(metricProbe));
      } else if (configId.startsWith(LOG_PROBE_PREFIX)) {
        LogProbe logProbe = deserializeLogProbe(content);
        configChunks.put(configId, definitions -> definitions.add(logProbe));
      } else if (configId.startsWith(SPAN_PROBE_PREFIX)) {
        SpanProbe spanProbe = deserializeSpanProbe(content);
        configChunks.put(configId, definitions -> definitions.add(spanProbe));
      } else if (configId.startsWith(TRIGGER_PROBE_PREFIX)) {
        TriggerProbe triggerProbe = deserializeTriggerProbe(content);
        configChunks.put(configId, definitions -> definitions.add(triggerProbe));
      } else if (configId.startsWith(SPAN_DECORATION_PROBE_PREFIX)) {
        SpanDecorationProbe spanDecorationProbe = deserializeSpanDecorationProbe(content);
        configChunks.put(configId, definitions -> definitions.add(spanDecorationProbe));
      } else if (IS_UUID.test(configId)) {
        Configuration newConfig = deserializeConfiguration(content);
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

    void add(MetricProbe probe) {
      definitions.add(probe);
    }

    void add(LogProbe probe) {
      definitions.add(probe);
    }

    void add(SpanProbe probe) {
      definitions.add(probe);
    }

    void add(TriggerProbe probe) {
      definitions.add(probe);
    }

    void add(SpanDecorationProbe probe) {
      definitions.add(probe);
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
