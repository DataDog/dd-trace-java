package datadog.trace.common.sampling

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

class SamplerTest extends DDSpecification {

  void "test that StandaloneSampler is selected when apm tracing disabled and appsec enabled"() {
    setup:
    System.setProperty("dd.apm.tracing.enabled", "false")
    System.setProperty("dd.appsec.enabled", "true")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof StandaloneSampler
  }

  void "test that StandaloneSampler is selected when apm tracing disabled and iast enabled"() {
    setup:
    System.setProperty("dd.apm.tracing.enabled", "false")
    System.setProperty("dd.iast.enabled", "true")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof StandaloneSampler
  }

  void "test that StandaloneSampler is selected when apm tracing disabled and sca enabled"() {
    setup:
    System.setProperty("dd.apm.tracing.enabled", "false")
    System.setProperty("dd.appsec.sca.enabled", "true")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof StandaloneSampler
  }

  void "test that StandaloneSampler is selected when apm tracing disabled and llmobs enabled"() {
    setup:
    System.setProperty("dd.apm.tracing.enabled", "false")
    System.setProperty("dd.llmobs.enabled", "true")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof StandaloneSampler
  }

  void "test that StandaloneSampler is selected when apm tracing disabled and both llmobs and asm enabled"() {
    setup:
    System.setProperty("dd.apm.tracing.enabled", "false")
    System.setProperty("dd.llmobs.enabled", "true")
    System.setProperty("dd.appsec.enabled", "true")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof StandaloneSampler
  }

  void "test that ForcePrioritySampler with SAMPLER_DROP is selected when apm tracing disabled and no other products enabled"() {
    setup:
    System.setProperty("dd.apm.tracing.enabled", "false")
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    sampler instanceof ForcePrioritySampler
  }

  void "test that StandaloneSampler is not selected when apm tracing enabled"() {
    setup:
    Config config = new Config()

    when:
    Sampler sampler = Sampler.Builder.forConfig(config, null)

    then:
    !(sampler instanceof StandaloneSampler)
  }
}
