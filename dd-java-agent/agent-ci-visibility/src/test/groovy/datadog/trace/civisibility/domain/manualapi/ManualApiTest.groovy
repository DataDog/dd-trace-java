package datadog.trace.civisibility.domain.manualapi

import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.civisibility.coverage.CoverageStore
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector
import datadog.trace.api.civisibility.telemetry.tag.Provider
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.codeowners.Codeowners
import datadog.trace.civisibility.decorator.TestDecoratorImpl
import datadog.trace.civisibility.domain.SpanWriterTest
import datadog.trace.civisibility.source.LinesResolver
import datadog.trace.civisibility.source.SourcePathResolver

class ManualApiTest extends SpanWriterTest {

  def "test framework tag is set on suite, test, module and session with component info"() {
    setup:
    def component = "my-custom-framework"
    def session = givenAManualApiSession(component, Stub(Config))
    def module = session.testModuleStart("module-name", null)
    def suite = module.testSuiteStart("suite-name", null, null, false)
    def test = suite.testStart("test-name", null, null)

    when:
    test.end(null)
    suite.end(null)
    module.end(null)
    session.end(null)

    then:
    def traces = TEST_WRITER.toList()
    traces.size() == 2

    def allSpans = traces.flatten()
    def sessionSpan = allSpans.find { it.spanType == DDSpanTypes.TEST_SESSION_END }
    def moduleSpan = allSpans.find { it.spanType == DDSpanTypes.TEST_MODULE_END }
    def suiteSpan = allSpans.find { it.spanType == DDSpanTypes.TEST_SUITE_END }
    def testSpan = allSpans.find { it.spanType == DDSpanTypes.TEST }

    sessionSpan != null
    moduleSpan != null
    suiteSpan != null
    testSpan != null

    sessionSpan.tags[Tags.TEST_FRAMEWORK] == component
    moduleSpan.tags[Tags.TEST_FRAMEWORK] == component
    suiteSpan.tags[Tags.TEST_FRAMEWORK] == component
    testSpan.tags[Tags.TEST_FRAMEWORK] == component
  }

  def "tags configured to propagate are inherited by child events"() {
    setup:
    def config = Stub(Config) {
      getCiVisibilityPropagatedTagKeys() >> (["custom.tag"] as Set)
    }
    def session = givenAManualApiSession("my-custom-framework", config)
    session.setTag("custom.tag", "custom-value")
    def module = session.testModuleStart("module-name", null)
    def suite = module.testSuiteStart("suite-name", null, null, false)
    def test = suite.testStart("test-name", null, null)

    when:
    test.end(null)
    suite.end(null)
    module.end(null)
    session.end(null)

    then:
    def allSpans = TEST_WRITER.toList().flatten()
    def sessionSpan = allSpans.find { it.spanType == DDSpanTypes.TEST_SESSION_END }
    def moduleSpan = allSpans.find { it.spanType == DDSpanTypes.TEST_MODULE_END }
    def suiteSpan = allSpans.find { it.spanType == DDSpanTypes.TEST_SUITE_END }
    def testSpan = allSpans.find { it.spanType == DDSpanTypes.TEST }

    sessionSpan.tags["custom.tag"] == "custom-value"
    moduleSpan.tags["custom.tag"] == "custom-value"
    suiteSpan.tags["custom.tag"] == "custom-value"
    testSpan.tags["custom.tag"] == "custom-value"
  }

  private ManualApiTestSession givenAManualApiSession(String component, Config config) {
    new ManualApiTestSession(
      "project-name",
      null,
      Provider.UNSUPPORTED,
      config,
      Stub(CiVisibilityMetricCollector),
      new TestDecoratorImpl(component, "session-name", "test-command", [:]),
      Stub(SourcePathResolver),
      Stub(Codeowners),
      Stub(LinesResolver),
      Stub(CoverageStore.Factory)
      )
  }
}
