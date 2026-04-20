package datadog.trace.instrumentation.selenium

import org.example.TestSucceedMultipleSelenium
import org.junit.jupiter.api.Assumptions
import spock.util.environment.Jvm

class SeleniumLatestTest extends AbstractSeleniumTest {

  def "test Selenium #testcaseName"() {
    // Latest Selenium versions require Java 11
    Assumptions.assumeTrue(Jvm.current.java11Compatible)

    runTests(tests)

    def dynamicData = assertSpansData(testcaseName, [
      "content.meta.['test.browser.driver_version']": SeleniumUtils.SELENIUM_VERSION,
    ])
    assertRumData(testCasesCount, dynamicData)

    where:
    testcaseName            | tests                         | testCasesCount
    "test-succeed-multiple" | [TestSucceedMultipleSelenium] | 2
  }
}
