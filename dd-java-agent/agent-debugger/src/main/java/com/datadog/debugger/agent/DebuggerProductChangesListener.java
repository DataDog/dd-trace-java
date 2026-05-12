package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.REMOTE_CONFIG;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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

  private final ConfigurationAcceptor configurationAcceptor;
  private final Map<String, ProbeDefinition> probeByConfigId = new HashMap<>();

  DebuggerProductChangesListener(ConfigurationAcceptor configurationAcceptor) {
    this.configurationAcceptor = configurationAcceptor;
  }

  @Override
  public void accept(ConfigKey configKey, byte[] content, PollingRateHinter pollingRateHinter)
      throws IOException {
    String configId = configKey.getConfigId();
    try {
      if (configId.startsWith(METRIC_PROBE_PREFIX)) {
        MetricProbe metricProbe = deserializeMetricProbe(content);
        probeByConfigId.put(configId, metricProbe);
      } else if (configId.startsWith(LOG_PROBE_PREFIX)) {
        LogProbe logProbe = deserializeLogProbe(content);
        probeByConfigId.put(configId, logProbe);
      } else if (configId.startsWith(SPAN_PROBE_PREFIX)) {
        SpanProbe spanProbe = deserializeSpanProbe(content);
        probeByConfigId.put(configId, spanProbe);
      } else if (configId.startsWith(TRIGGER_PROBE_PREFIX)) {
        TriggerProbe triggerProbe = deserializeTriggerProbe(content);
        probeByConfigId.put(configId, triggerProbe);
      } else if (configId.startsWith(SPAN_DECORATION_PROBE_PREFIX)) {
        SpanDecorationProbe spanDecorationProbe = deserializeSpanDecorationProbe(content);
        probeByConfigId.put(configId, spanDecorationProbe);
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
    probeByConfigId.remove(configKey.getConfigId());
  }

  @Override
  public void commit(PollingRateHinter pollingRateHinter) {
    // create a snapshot of the actual probes stored into probeByConfigId map
    configurationAcceptor.accept(REMOTE_CONFIG, new ArrayList<>(probeByConfigId.values()));
  }
}
