import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.instrumentation.junit4.JUnit4Utils

class JUnit4UtilsTest extends AgentTestRunner {

  @Override
  def setup() {
    CheckpointValidator.DONOTUSE_excludeValidations_DONOTUSE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SEQUENCE)
  }

  def "test remove trailing brackets from test name"() {
    when:
    def testNameNoParams = JUnit4Utils.normalizeTestName(testName)

    then:
    testNameNoParams == expectedTestNameNoParams

    where:
    testName         | expectedTestNameNoParams
    null             | null
    ""               | ""
    "sample"         | "sample"
    "sample[0]"      | "sample"
    "[0]sample"      | "[0]sample"
    "other[0]sample" | "other[0]sample"
  }
}
