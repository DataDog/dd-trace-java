import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.weaver.DatadogWeaverReporter
import datadog.trace.instrumentation.weaver.WeaverIntegrationTestRunner
import datadog.trace.instrumentation.weaver.WeaverUtils
import org.example.TestCanceled
import org.example.TestFailed
import org.example.TestAutomationExcluded
import org.example.TestAutomationDuplicateNames
import org.example.TestAutomationDuplicatePureNames
import org.example.TestAutomationNamed
import org.example.TestAutomationProvider
import org.example.TestFailedExceptionPure
import org.example.TestFailedPure
import org.example.TestIgnored
import org.example.TestSucceed
import org.example.TestSucceedGlobalResource
import org.example.TestSucceedPure
import org.example.TestSucceedSuiteResource

@DisableTestTrace(reason = "avoid self-tracing")
class WeaverTest extends CiVisibilityInstrumentationTest {

  def "test automation exclusions retain native outcomes and declaration tags"() {
    when:
    runTests([TestAutomationExcluded])

    then:
    def testSpans = TEST_WRITER.toList().flatten().findAll { it.getTag("test.name") == "protected_failure" || it.getTag("test.name") == "protected_skip" }
    testSpans.size() == 2
    def ordinary = TEST_WRITER.toList().flatten().find { it.getTag("test.name") == "ordinary_tagged" }
    ordinary != null
    ordinary.getTag("test.test_management.no_auto_retry") == null
    ordinary.getTag("test.test_management.prevent_failure_suppression") == null
    ordinary.getTag("test.traits") == null
    testSpans.every { it.getTag("test.test_management.no_auto_retry") == true }
    testSpans.every { it.getTag("test.test_management.prevent_failure_suppression") == true }
    testSpans.find { it.getTag("test.name") == "protected_failure" }.getTag("test.status").toString() == "fail"
    testSpans.find { it.getTag("test.name") == "protected_skip" }.getTag("test.status").toString() == "skip"
  }

  def "duplicate names retain each declaration's annotations and native selection in #testClass.simpleName"() {
    when:
    runTests([testClass])

    then:
    def spans = TEST_WRITER.toList().flatten().findAll { it.getTag("test.suite") == testClass.name && it.getTag("test.name") != null }
    spans.size() == expectedExecutions
    def ignoredCollision = spans.find { it.getTag("test.name") == "ignored_collision" }
    ignoredCollision.getTag("test.status").toString() == "pass"
    ignoredCollision.getTag("test.test_management.no_auto_retry") == null
    ignoredCollision.getTag("test.test_management.prevent_failure_suppression") == null
    def duplicates = spans.findAll { it.getTag("test.name") == "runnable_collision" }
    duplicates.size() == expectedExecutions - 3
    def failed = duplicates.find { it.getTag("test.status").toString() == "fail" }
    failed.getTag("test.test_management.no_auto_retry") == true
    failed.getTag("test.test_management.prevent_failure_suppression") == null
    def passed = duplicates.find { it.getTag("test.status").toString() == "pass" }
    passed.getTag("test.test_management.no_auto_retry") == null
    passed.getTag("test.test_management.prevent_failure_suppression") == true
    def skipped = duplicates.findAll { it.getTag("test.status").toString() == "skip" }
    skipped.size() == expectedExecutions - 5
    skipped.every { it.getTag("test.test_management.no_auto_retry") == null }
    skipped.every { it.getTag("test.test_management.prevent_failure_suppression") == null }
    def copied = spans.findAll { it.getTag("test.name") == "copy_removed" }
    copied.size() == 2
    def annotatedCopy = copied.find { it.getTag("test.status").toString() == "fail" }
    annotatedCopy.getTag("test.test_management.no_auto_retry") == true
    annotatedCopy.getTag("test.test_management.prevent_failure_suppression") == null
    def ordinaryCopy = copied.find { it.getTag("test.status").toString() == "pass" }
    ordinaryCopy.getTag("test.test_management.no_auto_retry") == null
    ordinaryCopy.getTag("test.test_management.prevent_failure_suppression") == null
    testClass.executionCount() == expectedExecutions
    testClass.ignoredExecutionCount() == 0

    where:
    testClass                        | expectedExecutions
    TestAutomationDuplicateNames     | 6
    TestAutomationDuplicatePureNames | 5
  }

  def "custom suite and provider identities preserve distinct declaration tags"() {
    when:
    DatadogWeaverReporter.start()
    WeaverIntegrationTestRunner.runTests([TestAutomationNamed.name, TestAutomationProvider.name])

    then:
    def spans = TEST_WRITER.toList().flatten().findAll { it.getTag("test.name") == "named_test" }
    spans.size() == 2
    def named = spans.find { it.getTag("test.suite") == TestAutomationNamed.name }
    named.getTag("test.test_management.no_auto_retry") == true
    named.getTag("test.test_management.prevent_failure_suppression") == null
    def provided = spans.find { it.getTag("test.suite") == TestAutomationProvider.name }
    provided.getTag("test.test_management.prevent_failure_suppression") == true
    provided.getTag("test.test_management.no_auto_retry") == null

    and:
    def snapshots = DatadogWeaverReporter.getDeclaredField("TEST_TAGS")
    snapshots.setAccessible(true)
    snapshots.get(null).isEmpty()

    cleanup:
    DatadogWeaverReporter.stop()
  }

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                   | tests
    "test-succeed-pure"            | [TestSucceedPure]
    "test-failed-pure"             | [TestFailedPure]
    "test-failed-exception-pure"   | [TestFailedExceptionPure]
    "test-succeeded"               | [TestSucceed]
    "test-failed"                  | [TestFailed]
    "test-ignored"                 | [TestIgnored]
    "test-canceled"                | [TestCanceled]
    "test-succeed-suite-resource"  | [TestSucceedSuiteResource]
    "test-succeed-global-resource" | [TestSucceedGlobalResource]
  }

  def "test capabilities tagging"() {
    setup:
    runTests([TestSucceed])

    expect:
    assertCapabilities(WeaverUtils.CAPABILITIES, 4)
  }

  @Override
  String instrumentedLibraryName() {
    return "weaver"
  }

  @Override
  String instrumentedLibraryVersion() {
    return WeaverUtils.weaverVersion
  }

  void runTests(List<Class<?>> tests) {
    DatadogWeaverReporter.start()
    def testNames = tests.collect { it.name }
    WeaverIntegrationTestRunner.runTests(testNames)
    DatadogWeaverReporter.stop()
  }
}
