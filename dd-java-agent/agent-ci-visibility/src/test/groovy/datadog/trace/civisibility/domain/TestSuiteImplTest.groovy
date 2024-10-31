package datadog.trace.civisibility.domain

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.tooling.TracerInstaller
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTraceId
import datadog.trace.api.IdGenerationStrategy
import datadog.trace.api.civisibility.coverage.CoverageStore
import datadog.trace.api.civisibility.coverage.NoOpCoverageStore
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.codeowners.NoCodeowners
import datadog.trace.civisibility.decorator.TestDecoratorImpl
import datadog.trace.civisibility.source.BestEffortSourcePathResolver
import datadog.trace.civisibility.source.MethodLinesResolver
import datadog.trace.civisibility.source.SourcePathResolver
import datadog.trace.civisibility.telemetry.CiVisibilityMetricCollectorImpl
import datadog.trace.civisibility.utils.SpanUtils
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class TestSuiteImplTest extends DDSpecification {

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

  def "test suite span is generated and test.codeowners populated"() {
    setup:
    def test = givenATest()

    when:
    test.end(null)

    then:
    ListWriterAssert.assertTraces(TEST_WRITER, 1, false, {
      trace(1) {
        span(0) {
          spanType DDSpanTypes.TEST_SUITE_END
          tags(false) {
            "$Tags.TEST_CODEOWNERS" "[\"@global-owner1\",\"@global-owner2\"]"
          }
        }
      }
    })
  }

  private static final class MyClass {}

  private TestSuiteImpl givenATest(
    CoverageStore.Factory coverageStoreFactory = new NoOpCoverageStore.Factory()) {
    def traceId = Stub(DDTraceId)
    traceId.toLong() >> 123

    def moduleSpanContext = Stub(AgentSpan.Context)
    moduleSpanContext.getSpanId() >> 456
    moduleSpanContext.getTraceId() >> traceId

    def testFramework = TestFrameworkInstrumentation.OTHER
    def config = Config.get()
    def metricCollector = Stub(CiVisibilityMetricCollectorImpl)
    def testDecorator = new TestDecoratorImpl("component", "session-name", "test-command", [:])
    def methodLinesResolver = { it -> MethodLinesResolver.MethodLines.EMPTY }
    def delegate = Stub(SourcePathResolver)
    def secondDelegate = Stub(SourcePathResolver)
    def resolver = new BestEffortSourcePathResolver(delegate, secondDelegate)
    delegate.getSourcePath(MyClass) >> "MyClass.java"
    secondDelegate.getSourcePath(MyClass) >> null
    def codeowners = Stub(NoCodeowners)
    codeowners.getOwners("MyClass.java") >> new ArrayList<String>(Arrays.asList("@global-owner1", "@global-owner2"))
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
      methodLinesResolver,
      coverageStoreFactory,
      SpanUtils.DO_NOT_PROPAGATE_CI_VISIBILITY_TAGS
      )
  }
}
