import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.api.civisibility.config.SkippableTestsSerializer
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.civisibility.CiVisibilityTest
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.example.TestParameterizedSpock
import org.example.TestSucceedSpock
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.spockframework.runtime.SpockEngine

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class SpockTest extends CiVisibilityTest {

  def "test success generate spans"() {
    setup:
    runTestClasses(TestSucceedSpock)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceedSpock", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedSpock", "test success", "test success()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test parameterized generate spans"() {
    setup:
    runTestClasses(TestParameterizedSpock)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestParameterizedSpock", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterizedSpock", "test add 1 and 2", "test add #a and #b(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", CIConstants.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterizedSpock", "test add 4 and 4", "test add #a and #b(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", CIConstants.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"test add 1 and 2"}}']
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"test add 4 and 4"}}']
  }

  def "test ITR test skipping"() {
    setup:
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_SKIPPABLE_TESTS, SkippableTestsSerializer.serialize([new SkippableTest("org.example.TestSucceedSpock", "test success", null, null),]))
    runTestClasses(TestSucceedSpock)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceedSpock", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedSpock", "test success", "test success()V", CIConstants.TEST_SKIP, testTags)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner"]
  }

  def "test ITR skipping for parameterized tests"() {
    setup:
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_SKIPPABLE_TESTS, SkippableTestsSerializer.serialize([
      new SkippableTest("org.example.TestParameterizedSpock", "test add 1 and 2", testTags_0[Tags.TEST_PARAMETERS], null),
    ]))
    runTestClasses(TestParameterizedSpock)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestParameterizedSpock", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterizedSpock", "test add 1 and 2", "test add #a and #b(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", CIConstants.TEST_SKIP, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterizedSpock", "test add 4 and 4", "test add #a and #b(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", CIConstants.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [
      (Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"test add 1 and 2"}}',
      (Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner"
    ]
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"test add 4 and 4"}}']
  }

  private static void runTestClasses(Class<?>... classes) {
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
    return "2.1.0-groovy-3.0"
  }

  @Override
  String component() {
    return "junit"
  }
}
