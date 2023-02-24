import datadog.trace.instrumentation.junit4.JUnit4Utils
import org.junit.runner.Description
import spock.lang.Specification

class JUnit4UtilsTest extends Specification {

  def "test get test name: #methodName"() {
    setup:
    def description = Stub(Description)
    description.getMethodName() >> methodName

    when:
    def testName = JUnit4Utils.getTestName(description, null)

    then:
    testName == expectedTestName

    where:
    methodName                       | expectedTestName
    null                             | null
    ""                               | null
    "sample"                         | "sample"
    "sample[0]"                      | "sample"
    "[0]sample"                      | "[0]sample"
    "other[0]sample"                 | "other[0]sample"
    "[0] 2, 2, 4 (actualMethodName)" | "actualMethodName"
  }
}
