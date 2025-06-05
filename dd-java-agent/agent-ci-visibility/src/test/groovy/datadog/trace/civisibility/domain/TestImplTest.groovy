package datadog.trace.civisibility.domain

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTraceId
import datadog.trace.api.civisibility.config.LibraryCapability
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.coverage.CoverageProbes
import datadog.trace.api.civisibility.coverage.CoverageStore
import datadog.trace.api.civisibility.coverage.NoOpCoverageStore
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.civisibility.codeowners.NoCodeowners
import datadog.trace.civisibility.decorator.TestDecoratorImpl
import datadog.trace.civisibility.source.LinesResolver
import datadog.trace.civisibility.source.NoOpSourcePathResolver
import datadog.trace.civisibility.telemetry.CiVisibilityMetricCollectorImpl
import datadog.trace.civisibility.test.ExecutionResults
import datadog.trace.civisibility.utils.SpanUtils

class TestImplTest extends SpanWriterTest {
  def "test span is generated and tags populated"() {
    setup:
    def test = givenATest()

    when:
    test.end(null)

    then:
    ListWriterAssert.assertTraces(TEST_WRITER, 1, false, {
      trace(1) {
        span(0) {
          parent()
          spanType DDSpanTypes.TEST
          tags(false) {
            "${LibraryCapability.TIA.asTag()}" "${LibraryCapability.TIA.getVersion()}"
            "${LibraryCapability.EFD.asTag()}" "${LibraryCapability.EFD.getVersion()}"
            "${LibraryCapability.QUARANTINE.asTag()}" "${LibraryCapability.QUARANTINE.getVersion()}"
            "${LibraryCapability.DISABLED.asTag()}" "${LibraryCapability.DISABLED.getVersion()}"
            "${LibraryCapability.ATTEMPT_TO_FIX.asTag()}" "${LibraryCapability.ATTEMPT_TO_FIX.getVersion()}"
          }
        }
      }
    })
  }

  def "test outstanding operation spans are closed"() {
    setup:
    def test = givenATest()

    // given operation that is started, but not closed
    AgentTracer.activateSpan(AgentTracer.get().startSpan("instrumentation", "span"))

    when:
    test.end(null)

    then:
    ListWriterAssert.assertTraces(TEST_WRITER, 1, false, ListWriterAssert.SORT_TRACES_BY_START, {
      trace(2) {
        long testId
        span(0) {
          parent()
          spanType DDSpanTypes.TEST
          testId = getSpan().getSpanId()
        }
        span(1) {
          parentSpanId(BigInteger.valueOf(testId))
        }
      }
    })
  }

  def "test coverage is not reported if test was skipped"() {
    setup:
    def coverageProbes = Mock(CoverageProbes)

    def coverageStore = Mock(CoverageStore)
    coverageStore.getProbes() >> coverageProbes

    def coverageStoreFactory = Stub(CoverageStore.Factory)
    coverageStoreFactory.create((TestIdentifier) _) >> coverageStore

    def test = givenATest(coverageStoreFactory)

    when:
    test.setSkipReason("skipped")
    test.end(null)

    then:
    0 * coverageStore.report(_, _, _)
  }

  private TestImpl givenATest(
    CoverageStore.Factory coverageStoreFactory = new NoOpCoverageStore.Factory()) {

    def traceId = Stub(DDTraceId)
    traceId.toLong() >> 123

    def moduleSpanContext = Stub(AgentSpanContext)
    moduleSpanContext.getSpanId() >> 456
    moduleSpanContext.getTraceId() >> traceId
    def suiteId = 789

    def testFramework = TestFrameworkInstrumentation.OTHER
    def config = Config.get()
    def metricCollector = Stub(CiVisibilityMetricCollectorImpl)
    def executionResults = Stub(ExecutionResults)
    def testDecorator = new TestDecoratorImpl("component", "session-name", "test-command", [:])

    def linesResolver = Stub(LinesResolver)
    linesResolver.getMethodLines(_) >> LinesResolver.Lines.EMPTY

    def codeowners = NoCodeowners.INSTANCE
    def libraryCapabilities = [
      LibraryCapability.TIA,
      LibraryCapability.EFD,
      LibraryCapability.QUARANTINE,
      LibraryCapability.DISABLED,
      LibraryCapability.ATTEMPT_TO_FIX
    ]

    new TestImpl(
      moduleSpanContext,
      suiteId,
      "moduleName",
      "suiteName",
      "testName",
      "testParameters",
      null,
      null,
      null,
      null,
      InstrumentationType.BUILD,
      testFramework,
      config,
      metricCollector,
      testDecorator,
      NoOpSourcePathResolver.INSTANCE,
      linesResolver,
      codeowners,
      coverageStoreFactory,
      executionResults,
      libraryCapabilities,
      SpanUtils.DO_NOT_PROPAGATE_CI_VISIBILITY_TAGS
      )
  }
}
