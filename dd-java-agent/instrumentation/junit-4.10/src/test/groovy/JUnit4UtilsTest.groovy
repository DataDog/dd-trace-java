import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DisableTestTrace
import datadog.trace.instrumentation.junit4.JUnit4Utils

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit4UtilsTest extends AgentTestRunner {

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
