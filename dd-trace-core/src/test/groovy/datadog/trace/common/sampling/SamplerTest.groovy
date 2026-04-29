package datadog.trace.common.sampling

import datadog.trace.api.Config
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification
import spock.lang.Unroll

class SamplerTest extends DDSpecification {

  @Unroll
  def 'sampler selection: apmEnabled=#apmEnabled llmobs=#llmobs appsec=#appsec iast=#iast sca=#sca → #expectedType.simpleName with activeProducts=#expectedProducts'() {
    setup:
    if (!apmEnabled) injectSysConfig("dd.apm.tracing.enabled", "false")
    if (llmobs)      injectSysConfig("dd.llmobs.enabled", "true")
    if (appsec)      injectSysConfig("dd.appsec.enabled", "true")
    if (iast)        injectSysConfig("dd.iast.enabled", "true")
    if (sca)         injectSysConfig("dd.appsec.sca.enabled", "true")

    when:
    Sampler sampler = Sampler.Builder.forConfig(Config.get(), null)

    then:
    expectedType.isInstance(sampler)
    expectedProducts == null || (sampler as StandaloneSampler).getActiveProducts() == expectedProducts

    where:
    apmEnabled | llmobs | appsec | iast  | sca   || expectedType              | expectedProducts
    true       | false  | false  | false | false || RateByServiceTraceSampler | null
    false      | true   | false  | false | false || StandaloneSampler         | [StandaloneProduct.LLMOBS]
    false      | false  | true   | false | false || StandaloneSampler         | [StandaloneProduct.ASM]
    false      | false  | false  | true  | false || StandaloneSampler         | [StandaloneProduct.ASM]
    false      | false  | false  | false | true  || StandaloneSampler         | [StandaloneProduct.ASM]
    false      | true   | true   | false | false || StandaloneSampler         | [StandaloneProduct.LLMOBS, StandaloneProduct.ASM]
    false      | false  | false  | false | false || ForcePrioritySampler      | null
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

  void "test that spans built with OTLP traces export enabled and priority sampling disabled have a non-UNSET sampling priority"() {
    setup:
    System.setProperty("dd.trace.otel.exporter", "otlp")
    System.setProperty("dd.priority.sampling", "false")
    Config config = new Config()
    Sampler sampler = Sampler.Builder.forConfig(config, null)
    CoreTracer tracer = CoreTracer.builder().writer(new ListWriter()).sampler(sampler).build()

    when:
    DDSpan span = (DDSpan) tracer.buildSpan("test").start()
    ((PrioritySampler) sampler).setSamplingPriority(span)

    then:
    span.getSamplingPriority() != null
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    cleanup:
    span.finish()
    tracer.close()
  }
}
