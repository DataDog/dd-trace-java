package datadog.trace.common.sampling;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.Config;
import datadog.trace.core.test.DDCoreSpecification;
import org.junit.jupiter.api.Test;

public class SamplerTest extends DDCoreSpecification {

  @Test
  void testThatAsmStandaloneSamplerIsSelectedWhenApmTracingDisabledAndAppsecEnabled() {
    System.setProperty("dd.apm.tracing.enabled", "false");
    System.setProperty("dd.appsec.enabled", "true");
    Config config = Config.get(System.getProperties());

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertTrue(sampler instanceof AsmStandaloneSampler);
  }

  @Test
  void testThatAsmStandaloneSamplerIsSelectedWhenApmTracingDisabledAndIastEnabled() {
    System.setProperty("dd.apm.tracing.enabled", "false");
    System.setProperty("dd.iast.enabled", "true");
    Config config = Config.get(System.getProperties());

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertTrue(sampler instanceof AsmStandaloneSampler);
  }

  @Test
  void testThatAsmStandaloneSamplerIsSelectedWhenApmTracingDisabledAndScaEnabled() {
    System.setProperty("dd.apm.tracing.enabled", "false");
    System.setProperty("dd.appsec.sca.enabled", "true");
    Config config = Config.get(System.getProperties());

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertTrue(sampler instanceof AsmStandaloneSampler);
  }

  @Test
  void testThatAsmStandaloneSamplerIsNotSelectedWhenApmTracingAndAsmNotEnabled() {
    System.setProperty("dd.apm.tracing.enabled", "false");
    Config config = Config.get(System.getProperties());

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertFalse(sampler instanceof AsmStandaloneSampler);
  }

  @Test
  void testThatAsmStandaloneSamplerIsNotSelectedWhenApmTracingEnabledAndAsmNotEnabled() {
    Config config = Config.get(System.getProperties());

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertFalse(sampler instanceof AsmStandaloneSampler);
  }
}
