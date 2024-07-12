package datadog.trace.civisibility

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.tooling.TracerInstaller
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.IdGenerationStrategy
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.coverage.CoverageProbes
import datadog.trace.api.civisibility.coverage.CoverageStore
import datadog.trace.api.civisibility.coverage.NoOpCoverageStore
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.civisibility.codeowners.NoCodeowners
import datadog.trace.civisibility.decorator.TestDecoratorImpl
import datadog.trace.civisibility.domain.TestImpl
import datadog.trace.civisibility.source.MethodLinesResolver
import datadog.trace.civisibility.source.NoOpSourcePathResolver
import datadog.trace.civisibility.telemetry.CiVisibilityMetricCollectorImpl
import datadog.trace.civisibility.utils.SpanUtils
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class TestImplTest extends DDSpecification {

  @SuppressWarnings('PropertyName')
  @Shared
  ListWriter TEST_WRITER

  @SuppressWarnings('PropertyName')
  @Shared
  AgentTracer.TracerAPI TEST_TRACER

  void setupSpec() {
    TEST_WRITER = new ListWriter()
    TEST_TRACER =
      Spy(
      CoreTracer.builder()
      .writer(TEST_WRITER)
      .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
      .build())
    TracerInstaller.forceInstallGlobalTracer(TEST_TRACER)

    TEST_TRACER.startSpan(*_) >> {
      def agentSpan = callRealMethod()
      agentSpan
    }
  }

  void cleanupSpec() {
    TEST_TRACER?.close()
  }

  void setup() {
    assert TEST_TRACER.activeSpan() == null: "Span is active before test has started: " + TEST_TRACER.activeSpan()
    TEST_TRACER.flush()
    TEST_WRITER.start()
  }

  void cleanup() {
    TEST_TRACER.flush()
  }

  def "test span is generated"() {
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

    def coveageStoreFactory = Stub(CoverageStore.Factory)
    coveageStoreFactory.create((TestIdentifier) _) >> coverageStore

    def test = givenATest(coveageStoreFactory)

    when:
    test.setSkipReason("skipped")
    test.end(null)

    then:
    0 * coverageStore.report(_, _, _)
  }

  private TestImpl givenATest(
    CoverageStore.Factory coverageStoreFactory = new NoOpCoverageStore.Factory()) {
    def sessionId = 123
    def moduleId = 456
    def suiteId = 789

    def testFramework = TestFrameworkInstrumentation.OTHER
    def config = Config.get()
    def metricCollector = Stub(CiVisibilityMetricCollectorImpl)
    def testDecorator = new TestDecoratorImpl("component", [:])
    def methodLinesResolver = { it -> MethodLinesResolver.MethodLines.EMPTY }
    def codeowners = NoCodeowners.INSTANCE
    new TestImpl(
      sessionId,
      moduleId,
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
      methodLinesResolver,
      codeowners,
      coverageStoreFactory,
      SpanUtils.DO_NOT_PROPAGATE_CI_VISIBILITY_TAGS
      )
  }

}
