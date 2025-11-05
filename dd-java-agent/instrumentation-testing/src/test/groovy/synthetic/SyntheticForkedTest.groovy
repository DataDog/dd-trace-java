package synthetic

import datadog.trace.agent.test.InstrumentationSpecification

abstract class SyntheticForkedTestBase extends InstrumentationSpecification {

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

    System.setProperty("synthetic.test.enabled", "true")
  }
}

class SyntheticDisabledForkedTest extends SyntheticForkedTestBase {

  @Override
  int computed(int i) {
    return i + 1
  }
}
