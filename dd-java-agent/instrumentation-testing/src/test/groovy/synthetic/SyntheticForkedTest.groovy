package synthetic

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.config.inversion.ConfigHelper

abstract class SyntheticForkedTestBase extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // Opt out of strict config validation - test module loads test instrumentations with fake names
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST)
  }

  def "test Synthetic methods"() {
    expect:
    SyntheticTestInstrumentation.Compute.result(i) == computed(i)

    where:
    i << [0, 1, 2, 3]
  }

  abstract int computed(int i)
}

class SyntheticForkedTest extends SyntheticForkedTestBase {

  @Override
  int computed(int i) {
    return i * 2 + 1
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // Opt out of strict config validation - test module loads test instrumentations with fake names
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST)

    System.setProperty("synthetic.test.enabled", "true")
  }
}

class SyntheticDisabledForkedTest extends SyntheticForkedTestBase {

  @Override
  int computed(int i) {
    return i + 1
  }
}
