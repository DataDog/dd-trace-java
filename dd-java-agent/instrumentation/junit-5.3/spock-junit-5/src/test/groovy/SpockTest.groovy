import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DDTags
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.api.civisibility.InstrumentationBridge
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.CiVisibilityTest
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import org.example.TestParameterizedSpock
import org.example.TestSucceedSpock
import org.example.TestSucceedSpockUnskippable
import org.example.TestSucceedSpockUnskippableSuite
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.spockframework.runtime.SpockEngine
import org.spockframework.util.SpockReleaseInfo

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class SpockTest extends CiVisibilityTest {

  def "test success generate spans"() {
    setup:
    runTestClasses(TestSucceedSpock)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSucceedSpock", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSucceedSpock", "test success", "test success()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test parameterized generate spans"() {
    setup:
    runTestClasses(TestParameterizedSpock)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestParameterizedSpock", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterizedSpock", "test add 1 and 2", "test add #a and #b(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", CIConstants.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterizedSpock", "test add 4 and 4", "test add #a and #b(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", CIConstants.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"test add 1 and 2"}}']
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"test add 4 and 4"}}']
  }

  def "test ITR test skipping"() {
    setup:
    givenSkippableTests([new SkippableTest("org.example.TestSucceedSpock", "test success", null, null),])
    runTestClasses(TestSucceedSpock)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP, [
          (DDTags.CI_ITR_TESTS_SKIPPED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_ENABLED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_TYPE): "test",
          (Tags.TEST_ITR_TESTS_SKIPPING_COUNT): 1,
        ])
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSucceedSpock", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSucceedSpock", "test success", "test success()V", CIConstants.TEST_SKIP, testTags)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner", (Tags.TEST_SKIPPED_BY_ITR): true]
  }

  def "test ITR skipping for parameterized tests"() {
    setup:
    givenSkippableTests([
      new SkippableTest("org.example.TestParameterizedSpock", "test add 1 and 2", testTags_0[Tags.TEST_PARAMETERS], null),
    ])
    runTestClasses(TestParameterizedSpock)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS, [
          (DDTags.CI_ITR_TESTS_SKIPPED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_ENABLED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_TYPE): "test",
          (Tags.TEST_ITR_TESTS_SKIPPING_COUNT): 1,
        ])
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestParameterizedSpock", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterizedSpock", "test add 1 and 2", "test add #a and #b(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", CIConstants.TEST_SKIP, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterizedSpock", "test add 4 and 4", "test add #a and #b(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", CIConstants.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [
      (Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"test add 1 and 2"}}',
      (Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner",
      (Tags.TEST_SKIPPED_BY_ITR): true
    ]
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"test add 4 and 4"}}']
  }

  def "test ITR unskippable"() {
    setup:
    givenSkippableTests([new SkippableTest("org.example.TestSucceedSpockUnskippable", "test success", null, null),])
    runTestClasses(TestSucceedSpockUnskippable)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS, [
          (Tags.TEST_ITR_TESTS_SKIPPING_ENABLED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_TYPE): "test",
          (Tags.TEST_ITR_TESTS_SKIPPING_COUNT): 0,
        ])
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSucceedSpockUnskippable", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSucceedSpockUnskippable", "test success", "test success()V", CIConstants.TEST_PASS,
          testTags, null, false, [InstrumentationBridge.ITR_UNSKIPPABLE_TAG])
      }
    })

    where:
    testTags = [(Tags.TEST_ITR_UNSKIPPABLE): true, (Tags.TEST_ITR_FORCED_RUN): true]
  }

  def "test ITR unskippable suite"() {
    setup:
    givenSkippableTests([new SkippableTest("org.example.TestSucceedSpockUnskippableSuite", "test success", null, null),])
    runTestClasses(TestSucceedSpockUnskippableSuite)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS, [
          (Tags.TEST_ITR_TESTS_SKIPPING_ENABLED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_TYPE): "test",
          (Tags.TEST_ITR_TESTS_SKIPPING_COUNT): 0,
        ])
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSucceedSpockUnskippableSuite", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSucceedSpockUnskippableSuite", "test success", "test success()V", CIConstants.TEST_PASS,
          testTags, null, false, [InstrumentationBridge.ITR_UNSKIPPABLE_TAG])
      }
    })

    where:
    testTags = [(Tags.TEST_ITR_UNSKIPPABLE): true, (Tags.TEST_ITR_FORCED_RUN): true]
  }

  private static void runTestClasses(Class<?>... classes) {
    TestEventsHandlerHolder.start()

    DiscoverySelector[] selectors = new DiscoverySelector[classes.length]
    for (i in 0..<classes.length) {
      selectors[i] = selectClass(classes[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectors)
      .build()

    def launcherConfig = LauncherConfig
      .builder()
      .enableTestEngineAutoRegistration(false)
      .addTestEngines(new SpockEngine())
      .build()

    def launcher = LauncherFactory.create(launcherConfig)
    launcher.execute(launcherReq)

    TestEventsHandlerHolder.stop()
  }

  @Override
  String expectedOperationPrefix() {
    return "junit"
  }

  @Override
  String expectedTestFramework() {
    return "spock"
  }

  @Override
  String expectedTestFrameworkVersion() {
    return SpockReleaseInfo.version
  }

  @Override
  String component() {
    return "junit"
  }
}
