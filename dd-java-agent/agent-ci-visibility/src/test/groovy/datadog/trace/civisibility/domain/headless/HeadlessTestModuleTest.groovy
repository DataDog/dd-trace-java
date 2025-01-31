package datadog.trace.civisibility.domain.headless

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.config.TestSourceData
import datadog.trace.api.civisibility.coverage.CoverageStore
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.civisibility.codeowners.Codeowners
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings
import datadog.trace.civisibility.config.ExecutionSettings
import datadog.trace.civisibility.decorator.TestDecorator
import datadog.trace.civisibility.domain.SpanWriterTest
import datadog.trace.civisibility.source.LinesResolver
import datadog.trace.civisibility.source.SourcePathResolver
import datadog.trace.civisibility.test.ExecutionStrategy

class HeadlessTestModuleTest extends SpanWriterTest {
  def "test module span is created and tags populated"() {
    setup:
    def headlessTestModule = givenAHeadlessTestModule()

    when:
    headlessTestModule.end(null)

    then:
    ListWriterAssert.assertTraces(TEST_WRITER, 1, false, {
      trace(1) {
        span(0) {
          spanType DDSpanTypes.TEST_MODULE_END
          tags(false) {
            "$DDTags.TEST_IS_USER_PROVIDED_SERVICE" true
          }
        }
      }
    })
  }

  def "test total retries limit is applied across test cases"() {
    given:
    def headlessTestModule = givenAHeadlessTestModule()

    when:
    def retryPolicy1 = headlessTestModule.retryPolicy(new TestIdentifier("suite", "test-1", null), TestSourceData.UNKNOWN)

    then:
    retryPolicy1.retry(false, 1L) // 2nd test execution, 1st retry globally
    !retryPolicy1.retry(false, 1L) // asking for 3rd test execution - local limit reached

    when:
    def retryPolicy2 = headlessTestModule.retryPolicy(new TestIdentifier("suite", "test-2", null), TestSourceData.UNKNOWN)

    then:
    retryPolicy2.retry(false, 1L) // 2nd test execution, 2nd retry globally (since previous test was retried too)
    !retryPolicy2.retry(false, 1L) // asking for 3rd test execution - local limit reached

    when:
    def retryPolicy3 = headlessTestModule.retryPolicy(new TestIdentifier("suite", "test-3", null), TestSourceData.UNKNOWN)

    then:
    !retryPolicy3.retry(false, 1L) // asking for 3rd retry globally - global limit reached
  }

  private HeadlessTestModule givenAHeadlessTestModule() {
    def executionSettings = Stub(ExecutionSettings)
    executionSettings.getEarlyFlakeDetectionSettings() >> EarlyFlakeDetectionSettings.DEFAULT
    executionSettings.isFlakyTestRetriesEnabled() >> true
    executionSettings.getFlakyTests() >> null

    def config = Stub(Config)
    config.getCiVisibilityFlakyRetryCount() >> 2
    // this counts all executions of a test case (first attempt is counted too)
    config.getCiVisibilityTotalFlakyRetryCount() >> 2
    // this counts retries across all tests (first attempt is not a retry, so it is not counted)
    config.isServiceNameSetByUser() >> true

    def executionStrategy = new ExecutionStrategy(config, executionSettings, Stub(SourcePathResolver), Stub(LinesResolver))

    new HeadlessTestModule(
    Stub(AgentSpanContext),
    "test-module",
    null,
    config,
    Stub(CiVisibilityMetricCollector),
    Stub(TestDecorator),
    Stub(SourcePathResolver),
    Stub(Codeowners),
    Stub(LinesResolver),
    Stub(CoverageStore.Factory),
    executionStrategy,
    (span) -> { }
    )
  }
}
