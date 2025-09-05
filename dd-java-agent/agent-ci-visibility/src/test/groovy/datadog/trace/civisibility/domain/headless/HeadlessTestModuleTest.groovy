package datadog.trace.civisibility.domain.headless

import datadog.trace.api.Config
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.config.TestSourceData
import datadog.trace.api.civisibility.coverage.CoverageStore
import datadog.trace.api.civisibility.execution.TestStatus
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
  def "test total retries limit is applied across test cases"() {
    given:
    def headlessTestModule = givenAHeadlessTestModule()

    when:
    def retryPolicy1 = headlessTestModule.executionPolicy(new TestIdentifier("suite", "test-1", null), TestSourceData.UNKNOWN, [])

    then:
    retryPolicy1.registerExecution(TestStatus.fail, 1L) // 1st test execution
    retryPolicy1.applicable()
    retryPolicy1.registerExecution(TestStatus.fail, 1L) // 2nd test execution, 1st retry globally
    !retryPolicy1.applicable() // asking for 3rd test execution - local limit reached

    when:
    def retryPolicy2 = headlessTestModule.executionPolicy(new TestIdentifier("suite", "test-2", null), TestSourceData.UNKNOWN, [])

    then:
    retryPolicy2.registerExecution(TestStatus.fail, 1L) // 1st test execution
    retryPolicy2.applicable()
    retryPolicy2.registerExecution(TestStatus.fail, 1L) // 2nd test execution, 1st retry globally
    !retryPolicy2.applicable() // asking for 3rd test execution - local limit reached

    when:
    def retryPolicy3 = headlessTestModule.executionPolicy(new TestIdentifier("suite", "test-3", null), TestSourceData.UNKNOWN, [])

    then:
    retryPolicy3.registerExecution(TestStatus.fail, 1L) // 1st test execution
    !retryPolicy3.applicable() // asking for 3rd retry globally - global limit reached
  }

  private HeadlessTestModule givenAHeadlessTestModule() {
    def executionSettings = Stub(ExecutionSettings)
    executionSettings.getEarlyFlakeDetectionSettings() >> EarlyFlakeDetectionSettings.DEFAULT
    executionSettings.isFlakyTestRetriesEnabled() >> true

    def config = Stub(Config)
    config.getCiVisibilityFlakyRetryCount() >> 2
    // this counts all executions of a test case (first attempt is counted too)
    config.getCiVisibilityTotalFlakyRetryCount() >> 2
    // this counts retries across all tests (first attempt is not a retry, so it is not counted)

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
    [],
    (span) -> { }
    )
  }
}
