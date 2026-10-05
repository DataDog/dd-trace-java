package datadog.trace.civisibility.domain.headless

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.civisibility.coverage.CoverageStore
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector
import datadog.trace.api.civisibility.telemetry.tag.HasCustomBuckets
import datadog.trace.api.civisibility.telemetry.tag.Provider
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.codeowners.Codeowners
import datadog.trace.civisibility.config.DynamicAutoTestRetrySettings
import datadog.trace.civisibility.config.ExecutionSettings
import datadog.trace.civisibility.config.ExecutionsByDuration
import datadog.trace.civisibility.config.TestManagementSettings
import datadog.trace.civisibility.decorator.TestDecorator
import datadog.trace.civisibility.domain.SpanWriterTest
import datadog.trace.civisibility.source.LinesResolver
import datadog.trace.civisibility.source.SourcePathResolver
import datadog.trace.civisibility.test.ExecutionStrategy

class HeadlessTestSessionTest extends SpanWriterTest {

  def "test tags are propagated correctly"() {
    setup:
    def session = givenAHeadlessTestSession()
    def module = session.testModuleStart("module-name", null)

    when:
    module.end(null)
    session.end(null)

    then:
    ListWriterAssert.assertTraces(TEST_WRITER, 1, false, {
      trace(2) {
        span(0) {
          spanType DDSpanTypes.TEST_SESSION_END
          tags(false) {
            "$Tags.TEST_TEST_MANAGEMENT_ENABLED" true
          }
        }
        span(1) {
          spanType DDSpanTypes.TEST_MODULE_END
        }
      }
    })
  }

  def "records dynamic ATR telemetry"() {
    setup:
    def metricCollector = Mock(CiVisibilityMetricCollector)
    def settings = DynamicAutoTestRetrySettings.create(true, customBuckets, backendBuckets)

    when:
    givenAHeadlessTestSession(settings, metricCollector)

    then:
    1 * metricCollector.add(CiVisibilityCountMetric.DYNAMIC_ATR_RETRIES_ENABLED, 1, {
      it.length == 1 && it[0] == expectedTag
    })

    where:
    customBuckets   | backendBuckets                                      | expectedTag
    [5, 4, 3, 2, 1] | []                                                  | HasCustomBuckets.TRUE
    null            | [new ExecutionsByDuration(Long.MAX_VALUE, 1)]       | null
  }

  def "does not record dynamic ATR telemetry when disabled"() {
    setup:
    def metricCollector = Mock(CiVisibilityMetricCollector)

    when:
    givenAHeadlessTestSession(DynamicAutoTestRetrySettings.DEFAULT, metricCollector)

    then:
    0 * metricCollector.add(CiVisibilityCountMetric.DYNAMIC_ATR_RETRIES_ENABLED, _, _)
  }

  private HeadlessTestSession givenAHeadlessTestSession() {
    givenAHeadlessTestSession(
      DynamicAutoTestRetrySettings.DEFAULT,
      Stub(CiVisibilityMetricCollector)
      )
  }

  private HeadlessTestSession givenAHeadlessTestSession(
    DynamicAutoTestRetrySettings dynamicAtrSettings,
    CiVisibilityMetricCollector metricCollector) {
    def executionSettings = Stub(ExecutionSettings)
    executionSettings.getDynamicAutoTestRetrySettings() >> dynamicAtrSettings
    executionSettings.getTestManagementSettings() >> new TestManagementSettings(true, 10)

    def executionStrategy = new ExecutionStrategy(Stub(Config), executionSettings, Stub(SourcePathResolver), Stub(LinesResolver))

    new HeadlessTestSession(
      "project-name",
      null,
      Provider.UNSUPPORTED,
      Stub(Config),
      metricCollector,
      Stub(TestDecorator),
      Stub(SourcePathResolver),
      Stub(Codeowners),
      Stub(LinesResolver),
      Stub(CoverageStore.Factory),
      executionStrategy,
      []
      )
  }
}
