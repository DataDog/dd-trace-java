package datadog.trace.common.sampling

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

class SamplerTest extends DDSpecification{

  void "test that TimeSampler is selected when experimentalAppSecStandalone is enabled"() {
    setup:
    System.setProperty("dd.experimental.appsec.standalone.enabled", "true")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof AsmStandaloneSampler
  }
}
