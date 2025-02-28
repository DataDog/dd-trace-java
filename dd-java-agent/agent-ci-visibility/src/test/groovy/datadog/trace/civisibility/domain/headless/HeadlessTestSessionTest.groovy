package datadog.trace.civisibility.domain.headless

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.civisibility.config.LibraryCapability
import datadog.trace.api.civisibility.coverage.CoverageStore
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector
import datadog.trace.api.civisibility.telemetry.tag.Provider
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation
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
    def suite = module.testSuiteStart("suite-name", MyClass, null, false, TestFrameworkInstrumentation.OTHER)
    def test = suite.testStart("test-name", null, null, null)

    when:
    test.end(null)
    suite.end(null)
    module.end(null)
    session.end(null)

    then:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, {
      trace(3) {
        span(0) {
          spanType DDSpanTypes.TEST_SESSION_END
          tags(false) {
            "$Tags.TEST_TEST_MANAGEMENT_ENABLED" true
            "$DDTags.LIBRARY_CAPABILITIES_TIA" false
            "$DDTags.LIBRARY_CAPABILITIES_EFD" false
            "$DDTags.LIBRARY_CAPABILITIES_QUARANTINE" true
            isNotPresent("$DDTags.LIBRARY_CAPABILITIES_ATTEMPT_TO_FIX")
          }
        }
        span(1) {
          spanType DDSpanTypes.TEST_MODULE_END
        }
        span(2) {
          spanType DDSpanTypes.TEST_SUITE_END
        }
      }
      trace(1) {
        span(0) {
          spanType DDSpanTypes.TEST
        }
      }
    })
  }

  private static final class MyClass {}

  private HeadlessTestSession givenAHeadlessTestSession() {
    def executionSettings = Stub(ExecutionSettings)
    executionSettings.getTestManagementSettings() >> new TestManagementSettings(true, 10)

    def executionStrategy = new ExecutionStrategy(Stub(Config), executionSettings, Stub(SourcePathResolver), Stub(LinesResolver))

    def availableCapabilities = [LibraryCapability.TIA, LibraryCapability.EFD, LibraryCapability.QUARANTINE]

    new HeadlessTestSession(
      "project-name",
      null,
      Provider.UNSUPPORTED,
      Stub(Config),
      Stub(CiVisibilityMetricCollector),
      Stub(TestDecorator),
      Stub(SourcePathResolver),
      Stub(Codeowners),
      Stub(LinesResolver),
      Stub(CoverageStore.Factory),
      executionStrategy,
      availableCapabilities
      )
  }
}
