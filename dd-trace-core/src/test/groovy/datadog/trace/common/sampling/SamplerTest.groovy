package datadog.trace.common.sampling

import datadog.trace.api.Config
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
}
