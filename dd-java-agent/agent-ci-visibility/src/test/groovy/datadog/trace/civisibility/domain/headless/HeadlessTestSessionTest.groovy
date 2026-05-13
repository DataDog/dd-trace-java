package datadog.trace.civisibility.domain.headless

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.civisibility.coverage.CoverageStore
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector
import datadog.trace.api.civisibility.telemetry.tag.Provider
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.codeowners.Codeowners
import datadog.trace.civisibility.config.ExecutionSettings
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
    module.setTag("custom.propagated_tag", "value")
    module.end(null)
    session.end(null)

    then:
    ListWriterAssert.assertTraces(TEST_WRITER, 1, false, {
      trace(2) {
        span(0) {
          spanType DDSpanTypes.TEST_SESSION_END
          tags(false) {
            "$Tags.TEST_TEST_MANAGEMENT_ENABLED" true
            "custom.propagated_tag" "value"
          }
        }
        span(1) {
          spanType DDSpanTypes.TEST_MODULE_END
          tags(false) {
            "custom.propagated_tag" "value"
          }
        }
      }
    })
  }

  private HeadlessTestSession givenAHeadlessTestSession() {
    def executionSettings = Stub(ExecutionSettings)
    executionSettings.getTestManagementSettings() >> new TestManagementSettings(true, 10)

    def config = Stub(Config)
    config.getCiVisibilityPropagatedTagKeys() >> ["custom.propagated_tag"]

    def executionStrategy = new ExecutionStrategy(config, executionSettings, Stub(SourcePathResolver), Stub(LinesResolver))

    new HeadlessTestSession(
      "project-name",
      null,
      Provider.UNSUPPORTED,
      config,
      Stub(CiVisibilityMetricCollector),
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
