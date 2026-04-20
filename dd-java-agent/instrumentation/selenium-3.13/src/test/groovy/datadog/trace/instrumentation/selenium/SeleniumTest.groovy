package datadog.trace.instrumentation.selenium

import org.example.TestSucceedSelenium

class SeleniumTest extends AbstractSeleniumTest {

  def "test Selenium #testcaseName"() {
    runTests(tests)

    def dynamicData = assertSpansData(testcaseName)
    assertRumData(testCasesCount, dynamicData)

    where:
    testcaseName            | tests                         | testCasesCount
    "test-succeed"          | [TestSucceedSelenium]         | 1
  }
}
