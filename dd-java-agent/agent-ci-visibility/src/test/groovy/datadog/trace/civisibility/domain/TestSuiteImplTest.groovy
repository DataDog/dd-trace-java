package datadog.trace.civisibility.domain

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTraceId
import datadog.trace.api.civisibility.coverage.CoverageStore
import datadog.trace.api.civisibility.coverage.NoOpCoverageStore
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.codeowners.NoCodeowners
import datadog.trace.civisibility.decorator.TestDecoratorImpl
import datadog.trace.civisibility.source.LinesResolver
import datadog.trace.civisibility.source.SourcePathResolver
import datadog.trace.civisibility.telemetry.CiVisibilityMetricCollectorImpl
import datadog.trace.civisibility.test.ExecutionResults
import datadog.trace.civisibility.utils.SpanUtils

class TestSuiteImplTest extends SpanWriterTest {
  def "test suite span is generated and tags populated"() {
    setup:
    def testSuite = givenATestSuite()

    when:
    testSuite.end(null)

    then:
    ListWriterAssert.assertTraces(TEST_WRITER, 1, false, {
      trace(1) {
        span(0) {
          spanType DDSpanTypes.TEST_SUITE_END
          tags(false) {
            "$Tags.TEST_CODEOWNERS" "[\"@global-owner1\",\"@global-owner2\"]"
            "$Tags.TEST_SOURCE_START" 10
            "$Tags.TEST_SOURCE_END" 20
          }
        }
      }
    })
  }

  private static final class MyClass {}

  private TestSuiteImpl givenATestSuite(
    CoverageStore.Factory coverageStoreFactory = new NoOpCoverageStore.Factory()) {
    def traceId = Stub(DDTraceId)
    traceId.toLong() >> 123

    def moduleSpanContext = Stub(AgentSpanContext)
    moduleSpanContext.getSpanId() >> 456
    moduleSpanContext.getTraceId() >> traceId

    def testFramework = TestFrameworkInstrumentation.OTHER
    def config = Config.get()
    def metricCollector = Stub(CiVisibilityMetricCollectorImpl)
    def executionResults = Stub(ExecutionResults)
    def testDecorator = new TestDecoratorImpl("component", "session-name", "test-command", [:])

    def linesResolver = Stub(LinesResolver)
    def classLines = new LinesResolver.Lines(10, 20)
    linesResolver.getClassLines(MyClass) >> classLines

    def resolver = Stub(SourcePathResolver)
    resolver.getSourcePath(MyClass) >> "MyClass.java"

    def codeowners = Stub(NoCodeowners)
    codeowners.getOwners("MyClass.java") >> ["@global-owner1", "@global-owner2"]

    new TestSuiteImpl(
      moduleSpanContext,
      "moduleName",
      "suiteName",
      "",
      MyClass,
      null,
      false,
      InstrumentationType.BUILD,
      testFramework,
      config,
      metricCollector,
      testDecorator,
      resolver,
      codeowners,
      linesResolver,
      coverageStoreFactory,
      executionResults,
      [],
      SpanUtils.DO_NOT_PROPAGATE_CI_VISIBILITY_TAGS
      )
  }
}
