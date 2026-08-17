package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.TriggerProbe;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigurationFileLoaderTest {

  @Test
  public void load() throws Exception {
    Path probeFilePath =
        Paths.get(ConfigurationFileLoaderTest.class.getResource("/test_probe_file.json").toURI());
    Configuration configuration = ConfigurationFileLoader.from(probeFilePath, 1024 * 1024);
    assertNotNull(configuration);
    List<ProbeDefinition> definitions = configuration.getDefinitions();
    assertEquals(6, definitions.size());
    assertInstanceOf(TriggerProbe.class, definitions.get(0));
    assertInstanceOf(MetricProbe.class, definitions.get(1));
    assertInstanceOf(LogProbe.class, definitions.get(2));
    assertInstanceOf(LogProbe.class, definitions.get(3));
    assertInstanceOf(SpanProbe.class, definitions.get(4));
    assertInstanceOf(SpanDecorationProbe.class, definitions.get(5));
  }
}
