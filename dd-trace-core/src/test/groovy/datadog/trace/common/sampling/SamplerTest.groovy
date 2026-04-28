package datadog.trace.common.sampling

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

class SamplerTest extends DDSpecification{

  void "test that AsmStandaloneSampler is selected when apm tracing disabled and appsec enabled is enabled"() {
    setup:
    System.setProperty("dd.apm.tracing.enabled", "false")
    System.setProperty("dd.appsec.enabled", "true")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof AsmStandaloneSampler
  }

  void "test that AsmStandaloneSampler is selected when apm tracing disabled and iast enabled is enabled"() {
    setup:
    System.setProperty("dd.apm.tracing.enabled", "false")
    System.setProperty("dd.iast.enabled", "true")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof AsmStandaloneSampler
  }

  void "test that AsmStandaloneSampler is selected when apm tracing disabled and sca enabled is enabled"() {
    setup:
    System.setProperty("dd.apm.tracing.enabled", "false")
    System.setProperty("dd.appsec.sca.enabled", "true")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof AsmStandaloneSampler
  }

  void "test that AsmStandaloneSampler is not selected when apm tracing and asm not enabled"() {
    setup:
    System.setProperty("dd.apm.tracing.enabled", "false")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    !(sampler instanceof AsmStandaloneSampler)
  }

  void "test that AsmStandaloneSampler is not selected when apm tracing enabled and asm not enabled"() {
    setup:
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    !(sampler instanceof AsmStandaloneSampler)
  }

  void "test that ParentBasedAlwaysOnSampler replaces AllSampler when OTLP traces export is enabled and priority sampling is disabled"() {
    setup:
    System.setProperty("dd.trace.otel.exporter", "otlp")
    System.setProperty("dd.priority.sampling", "false")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof ParentBasedAlwaysOnSampler
  }

  void "test that AllSampler is selected when OTLP traces export is disabled and priority sampling is disabled"() {
    setup:
    System.setProperty("dd.priority.sampling", "false")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof AllSampler
    !(sampler instanceof ParentBasedAlwaysOnSampler)
  }

  void "test that trace sampling rules are respected when OTLP traces export is enabled"() {
    setup:
    System.setProperty("dd.trace.otel.exporter", "otlp")
    System.setProperty("dd.trace.sample.rate", "0.5")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof RuleBasedTraceSampler
    !(sampler instanceof ParentBasedAlwaysOnSampler)
  }

  void "test that ParentBasedAlwaysOnSampler replaces RateByServiceTraceSampler when OTLP traces export is enabled with default priority sampling"() {
    setup:
    System.setProperty("dd.trace.otel.exporter", "otlp")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof ParentBasedAlwaysOnSampler
    !(sampler instanceof RateByServiceTraceSampler)
  }

  void "test that ForcePrioritySampler is respected when OTLP traces export is enabled and priority sampling is forced to keep"() {
    setup:
    System.setProperty("dd.trace.otel.exporter", "otlp")
    System.setProperty("dd.priority.sampling.force", "keep")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof ForcePrioritySampler
    !(sampler instanceof ParentBasedAlwaysOnSampler)
  }

  void "test that ForcePrioritySampler is respected when OTLP traces export is enabled and priority sampling is forced to drop"() {
    setup:
    System.setProperty("dd.trace.otel.exporter", "otlp")
    System.setProperty("dd.priority.sampling.force", "drop")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof ForcePrioritySampler
    !(sampler instanceof ParentBasedAlwaysOnSampler)
  }
}
