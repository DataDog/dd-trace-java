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

  void "test that ParentBasedAlwaysOnSampler is selected when otlp traces exporter enabled"() {
    setup:
    System.setProperty("dd.trace.otel.exporter", "otlp")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof ParentBasedAlwaysOnSampler
  }

  void "test that ParentBasedAlwaysOnSampler is not selected when otlp traces exporter not set"() {
    setup:
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    !(sampler instanceof ParentBasedAlwaysOnSampler)
  }

  void "test that user-defined sampling rules take precedence over otlp default sampler"() {
    setup:
    System.setProperty("dd.trace.otel.exporter", "otlp")
    System.setProperty("dd.trace.sample.rate", "0.5")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof RuleBasedTraceSampler
  }

  void "test that DD_TRACE_SAMPLING_RULES takes precedence over otlp default sampler"() {
    setup:
    System.setProperty("dd.trace.otel.exporter", "otlp")
    System.setProperty("dd.trace.sampling.rules", '[{"sample_rate": 0.5}]')
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof RuleBasedTraceSampler
  }
}
