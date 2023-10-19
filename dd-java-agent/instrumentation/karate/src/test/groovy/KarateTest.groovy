import com.intuit.karate.FileUtils
import com.intuit.karate.KarateException
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDTags
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.CiVisibilityTest
import datadog.trace.instrumentation.karate.TestEventsHandlerHolder
import org.example.TestFailedKarate
import org.example.TestParameterizedKarate
import org.example.TestSucceedKarate
import org.example.TestUnskippableKarate
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class KarateTest extends CiVisibilityTest {

  def "test success generates spans"() {
    setup:
    runTests(TestSucceedKarate)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "[org/example/test_succeed] test succeed", CIConstants.TEST_PASS, null, null, false, ['foo'], false)
      }
      trace(2, true) {
        long testId = testSpan(it, 1, testSessionId, testModuleId, testSuiteId, "[org/example/test_succeed] test succeed", "first scenario", null, CIConstants.TEST_PASS, null, null, false, ['bar', 'foo'], false, false)
        karateStepSpan(it, 0, testId, "* print 'first'", 6, 6)
      }
      trace(2, true) {
        long testId = testSpan(it, 1, testSessionId, testModuleId, testSuiteId, "[org/example/test_succeed] test succeed", "second scenario", null, CIConstants.TEST_PASS, null, null, false, ['foo'], false, false)
        karateStepSpan(it, 0, testId, "* print 'second'", 9, 9)
      }
    })
  }

  def "test parameterized generates spans"() {
    setup:
    runTests(TestParameterizedKarate)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_START, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "[org/example/test_parameterized] test parameterized", CIConstants.TEST_PASS, null, null, false, null, false)
      }
      trace(4, true) {
        long testId = testSpan(it, 3, testSessionId, testModuleId, testSuiteId, "[org/example/test_parameterized] test parameterized", "first scenario as an outline", null, CIConstants.TEST_PASS, testTags_0, null, false, null, false, false)
        karateStepSpan(it, 0, testId, "Given def p = 'a'", 6, 6)
        karateStepSpan(it, 2, testId, "When def response = p + p", 7, 7)
        karateStepSpan(it, 1, testId, "Then match response == value", 8, 8)
      }
      trace(4, true) {
        long testId = testSpan(it, 3, testSessionId, testModuleId, testSuiteId, "[org/example/test_parameterized] test parameterized", "first scenario as an outline", null, CIConstants.TEST_PASS, testTags_1, null, false, null, false, false)
        karateStepSpan(it, 0, testId, "Given def p = 'b'", 6, 6)
        karateStepSpan(it, 2, testId, "When def response = p + p", 7, 7)
        karateStepSpan(it, 1, testId, "Then match response == value", 8, 8)
      }
    })

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): '{"param":"\\\'a\\\'","value":"aa"}']
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"param":"\\\'b\\\'","value":"bb"}']
  }

  def "test failed generates spans"() {
    setup:
    runTests(TestFailedKarate)

    expect:
    def exception = new KarateException("did not evaluate to 'true': false\nclasspath:org/example/test_failed.feature:7")
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_FAIL)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "[org/example/test_failed] test failed", CIConstants.TEST_FAIL, null, exception, false, null, false)
      }
      trace(2, true) {
        long testId = testSpan(it, 1, testSessionId, testModuleId, testSuiteId, "[org/example/test_failed] test failed", "first scenario", null, CIConstants.TEST_PASS, null, null, false, null, false, false)
        karateStepSpan(it, 0, testId, "* print 'first'", 4, 4)
      }
      trace(2, true) {
        long testId = testSpan(it, 1, testSessionId, testModuleId, testSuiteId, "[org/example/test_failed] test failed", "second scenario", null, CIConstants.TEST_FAIL, null, exception, false, null, false, false)
        karateStepSpan(it, 0, testId, "* assert false", 7, 7)
      }
    })
  }

  def "test ITR skipping"() {
    setup:
    Assumptions.assumeTrue(isSkippingSupported(FileUtils.KARATE_VERSION), "Current Karate version $FileUtils.KARATE_VERSION does not support testSkipping")

    givenSkippableTests([
      new SkippableTest("[org/example/test_succeed] test succeed", "first scenario", null, null),
    ])
    runTests(TestSucceedKarate)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_START, {
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
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "[org/example/test_succeed] test succeed", CIConstants.TEST_PASS, null, null, false, ['foo'], false)
      }
      trace(1, true) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "[org/example/test_succeed] test succeed", "first scenario", null, CIConstants.TEST_SKIP, testTags, null, false, ['bar', 'foo'], false, false)
      }
      trace(2, true) {
        long testId = testSpan(it, 1, testSessionId, testModuleId, testSuiteId, "[org/example/test_succeed] test succeed", "second scenario", null, CIConstants.TEST_PASS, null, null, false, ['foo'], false, false)
        karateStepSpan(it, 0, testId, "* print 'second'", 9, 9)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner", (Tags.TEST_SKIPPED_BY_ITR): true ]
  }

  def "test parameterized ITR skipping"() {
    setup:
    Assumptions.assumeTrue(isSkippingSupported(FileUtils.KARATE_VERSION), "Current Karate version $FileUtils.KARATE_VERSION does not support testSkipping")

    givenSkippableTests([
      new SkippableTest("[org/example/test_parameterized] test parameterized", "first scenario as an outline", '{"param":"\\\'a\\\'","value":"aa"}', null),
    ])

    runTests(TestParameterizedKarate)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_START, {
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
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "[org/example/test_parameterized] test parameterized", CIConstants.TEST_PASS, null, null, false, null, false)
      }
      trace(1, true) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "[org/example/test_parameterized] test parameterized", "first scenario as an outline", null, CIConstants.TEST_SKIP, testTags_0, null, false, null, false, false)
      }
      trace(4, true) {
        long testId = testSpan(it, 3, testSessionId, testModuleId, testSuiteId, "[org/example/test_parameterized] test parameterized", "first scenario as an outline", null, CIConstants.TEST_PASS, testTags_1, null, false, null, false, false)
        karateStepSpan(it, 0, testId, "Given def p = 'b'", 6, 6)
        karateStepSpan(it, 2, testId, "When def response = p + p", 7, 7)
        karateStepSpan(it, 1, testId, "Then match response == value", 8, 8)
      }
    })

    where:
    testTags_0 = [
      (Tags.TEST_PARAMETERS): '{"param":"\\\'a\\\'","value":"aa"}',
      (Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner",
      (Tags.TEST_SKIPPED_BY_ITR): true
    ]
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"param":"\\\'b\\\'","value":"bb"}']
  }

  def "test ITR unskippable"() {
    setup:
    Assumptions.assumeTrue(isSkippingSupported(FileUtils.KARATE_VERSION), "Current Karate version $FileUtils.KARATE_VERSION does not support testSkipping")

    givenSkippableTests([
      new SkippableTest("[org/example/test_unskippable] test unskippable", "first scenario", null, null),
    ])
    runTests(TestUnskippableKarate)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_START, {
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
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "[org/example/test_unskippable] test unskippable", CIConstants.TEST_PASS, null, null, false, ['foo'], false)
      }
      trace(2, true) {
        long testId = testSpan(it, 1, testSessionId, testModuleId, testSuiteId, "[org/example/test_unskippable] test unskippable", "first scenario", null, CIConstants.TEST_PASS, testTags, null, false, ['bar', 'datadog_itr_unskippable', 'foo'], false, false)
        karateStepSpan(it, 0, testId, "* print 'first'", 7, 7)
      }
      trace(2, true) {
        long testId = testSpan(it, 1, testSessionId, testModuleId, testSuiteId, "[org/example/test_unskippable] test unskippable", "second scenario", null, CIConstants.TEST_PASS, null, null, false, ['foo'], false, false)
        karateStepSpan(it, 0, testId, "* print 'second'", 10, 10)
      }
    })

    where:
    testTags = [(Tags.TEST_ITR_UNSKIPPABLE): true, (Tags.TEST_ITR_FORCED_RUN): true]
  }

  private static void karateStepSpan(TraceAssert trace,
    int index,
    long testId,
    String stepName,
    int startLine,
    int endLine,
    String docString = null) {
    trace.span(index, {
      parentSpanId(BigInteger.valueOf(testId))
      operationName "karate.step"
      resourceName stepName
      tags {
        "$Tags.COMPONENT" "karate"
        "step.name" stepName
        "step.startLine" startLine
        "step.endLine" endLine

        if (docString != null) {
          "step.docString" docString
        }

        "$Tags.ENV" String
        "$DDTags.LIBRARY_VERSION_TAG_KEY" String

        defaultTags()
      }
    })
  }

  private void runTests(Class<?>... tests) {
    TestEventsHandlerHolder.start()

    DiscoverySelector[] selectors = new DiscoverySelector[tests.length]
    for (i in 0..<tests.length) {
      selectors[i] = selectClass(tests[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectors)
      .build()

    def launcherConfig = LauncherConfig
      .builder()
      .enableTestEngineAutoRegistration(false)
      .addTestEngines(new JupiterTestEngine())
      .build()

    def launcher = LauncherFactory.create(launcherConfig)
    launcher.execute(launcherReq)

    TestEventsHandlerHolder.stop()
  }

  @Override
  String expectedOperationPrefix() {
    return "karate"
  }

  @Override
  String expectedTestFramework() {
    return "karate"
  }

  @Override
  String expectedTestFrameworkVersion() {
    return FileUtils.KARATE_VERSION
  }

  @Override
  String component() {
    return "karate"
  }

  boolean isSkippingSupported(String frameworkVersion) {
    // earlier Karate version contain a bug that does not allow skipping scenarios
    frameworkVersion >= "1.2.0"
  }
}
