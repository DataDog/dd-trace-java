import datadog.trace.agent.test.base.TestFrameworkTest
import datadog.trace.api.DisableTestTrace
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.civisibility.Constants
import datadog.trace.instrumentation.junit5.JUnit5Decorator
import org.example.TestAssumption
import org.example.TestAssumptionAndSucceed
import org.example.TestAssumptionLegacy
import org.example.TestError
import org.example.TestFactory
import org.example.TestFailed
import org.example.TestFailedAndSucceed
import org.example.TestFailedSuiteSetup
import org.example.TestFailedSuiteTearDown
import org.example.TestInheritance
import org.example.TestParameterized
import org.example.TestRepeated
import org.example.TestSkipped
import org.example.TestSkippedClass
import org.example.TestSkippedNested
import org.example.TestSucceed
import org.example.TestSucceedAndSkipped
import org.example.TestSucceedNested
import org.example.TestSucceedWithCategories
import org.example.TestSuiteSetUpAssumption
import org.example.TestTemplate
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.opentest4j.AssertionFailedError

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit5Test extends TestFrameworkTest {

  def "test success generate spans"() {
    setup:
    runTestClasses(TestSucceed)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, Constants.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestSucceed", Constants.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceed", "test_succeed", Constants.TEST_PASS)
      }
    }
  }

  def "test inheritance generates spans"() {
    setup:
    runTestClasses(TestInheritance)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, Constants.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestInheritance", Constants.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestInheritance", "test_succeed", Constants.TEST_PASS)
      }
    }
  }

  def "test parameterized generates spans"() {
    setup:
    runTestClasses(TestParameterized)

    expect:
    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, Constants.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestParameterized", Constants.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "test_parameterized", Constants.TEST_PASS, testTags_1)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestParameterized", "test_parameterized", Constants.TEST_PASS, testTags_0)
      }
    }

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"[1] 0, 0, 0, some:\\\"parameter\\\""}}']
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"[2] 1, 1, 2, some:\\\"parameter\\\""}}']
  }

  def "test repeated generates spans"() {
    setup:
    runTestClasses(TestRepeated)

    expect:
    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, Constants.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestRepeated", Constants.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestRepeated", "test_repeated", Constants.TEST_PASS)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestRepeated", "test_repeated", Constants.TEST_PASS)
      }
    }
  }

  def "test template generates spans"() {
    setup:
    runTestClasses(TestTemplate)

    expect:
    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, Constants.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestTemplate", Constants.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestTemplate", "test_template", Constants.TEST_PASS, testTags_1)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestTemplate", "test_template", Constants.TEST_PASS, testTags_0)
      }
    }

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): "{\"metadata\":{\"test_name\":\"test_template_1\"}}"]
    testTags_1 = [(Tags.TEST_PARAMETERS): "{\"metadata\":{\"test_name\":\"test_template_2\"}}"]
  }

  def "test factory generates spans"() {
    setup:
    runTestClasses(TestFactory)

    expect:
    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, Constants.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestFactory", Constants.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFactory", "test_factory", Constants.TEST_PASS)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestFactory", "test_factory", Constants.TEST_PASS)
      }
    }
  }

  def "test failed generates spans"() {
    setup:
    runTestClassesSuppressingExceptions(TestFailed)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, Constants.TEST_FAIL)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestFailed", Constants.TEST_FAIL)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailed", "test_failed", Constants.TEST_FAIL, null, exception)
      }
    }

    where:
    exception = new AssertionFailedError("expected: <true> but was: <false>")
  }

  def "test error generates spans"() {
    setup:
    runTestClassesSuppressingExceptions(TestError)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, Constants.TEST_FAIL)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestError", Constants.TEST_FAIL)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestError", "test_error", Constants.TEST_FAIL, null, exception)
      }
    }

    where:
    exception = new IllegalArgumentException("This exception is an example")
  }

  def "test skipped generates spans"() {
    setup:
    runTestClasses(TestSkipped)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, Constants.TEST_SKIP)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestSkipped", Constants.TEST_SKIP)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkipped", "test_skipped", Constants.TEST_SKIP, testTags, null, true)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test class skipped generated spans"() {
    setup:
    runTestClasses(TestSkippedClass)

    expect:
    assertTraces(1) {
      trace(6, true) {
        long testModuleId = testModuleSpan(it, 4, Constants.TEST_SKIP)
        long testSuiteId = testSuiteSpan(it, 5, testModuleId, "org.example.TestSkippedClass", Constants.TEST_SKIP, testTags)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_case_skipped", Constants.TEST_SKIP, testTags, null, true)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_factory_skipped", Constants.TEST_SKIP, testTags, null, true)
        testSpan(it, 2, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_parameterized_skipped", Constants.TEST_SKIP, parameterizedTestTags, null, true)
        testSpan(it, 3, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_repeated_skipped", Constants.TEST_SKIP, testTags, null, true)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in class"]
    parameterizedTestTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in class", (Tags.TEST_PARAMETERS): "{\"metadata\":{\"test_name\":\"test_parameterized_skipped(int, int, int, String)\"}}"]
  }

  def "test with failing assumptions generated spans"() {
    setup:
    runTestClasses(TestAssumption)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, Constants.TEST_SKIP)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestAssumption", Constants.TEST_SKIP)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumption", "test_fail_assumption", Constants.TEST_SKIP, testTags)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Assumption failed: assumption is not true"]
  }

  def "test with failing legacy assumptions generated spans"() {
    setup:
    runTestClasses(TestAssumptionLegacy)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, Constants.TEST_SKIP)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestAssumptionLegacy", Constants.TEST_SKIP)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumptionLegacy", "test_fail_assumption_legacy", Constants.TEST_SKIP, testTags)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "assumption is not fulfilled"]
  }

  def "test success and skipped generates spans"() {
    setup:
    runTestClasses(TestSucceedAndSkipped)

    expect:
    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, Constants.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestSucceedAndSkipped", Constants.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", Constants.TEST_SKIP, testTags, null, true)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_succeed", Constants.TEST_PASS)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test success and failure generates spans"() {
    setup:
    runTestClasses(TestFailedAndSucceed)

    expect:
    assertTraces(1) {
      trace(5, true) {
        long testModuleId = testModuleSpan(it, 3, Constants.TEST_FAIL)
        long testSuiteId = testSuiteSpan(it, 4, testModuleId, "org.example.TestFailedAndSucceed", Constants.TEST_FAIL)
        testSpan(it, 2, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", Constants.TEST_PASS)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_failed", Constants.TEST_FAIL, null, exception)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", Constants.TEST_PASS)
      }
    }

    where:
    exception = new AssertionFailedError("expected: <true> but was: <false>")
  }

  def "test suite teardown failure generates spans"() {
    setup:
    runTestClasses(TestFailedSuiteTearDown)

    expect:
    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, Constants.TEST_FAIL)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestFailedSuiteTearDown", Constants.TEST_FAIL, null, exception)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestFailedSuiteTearDown", "test_succeed", Constants.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedSuiteTearDown", "test_another_succeed", Constants.TEST_PASS)
      }
    }

    where:
    exception = new RuntimeException("suite tear down failed")
  }

  def "test suite setup failure generates spans"() {
    setup:
    runTestClasses(TestFailedSuiteSetup)

    expect:
    assertTraces(1) {
      trace(2, true) {
        long testModuleId = testModuleSpan(it, 0, Constants.TEST_FAIL)
        testSuiteSpan(it, 1, testModuleId, "org.example.TestFailedSuiteSetup", Constants.TEST_FAIL, null, exception)
      }
    }

    where:
    exception = new RuntimeException("suite set up failed")
  }

  def "test categories are included in spans"() {
    setup:
    runTestClasses(TestSucceedWithCategories)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, Constants.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestSucceedWithCategories",
          Constants.TEST_PASS, null, null, false,
          ["Slow", "Flaky"])
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedWithCategories", "test_succeed",
          Constants.TEST_PASS, null, null, false,
          ["End2end", "Browser", "Slow", "Flaky"])
      }
    }
  }

  def "test assumption failure during suite setup"() {
    setup:
    runTestClasses(TestSuiteSetUpAssumption)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, Constants.TEST_SKIP)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestSuiteSetUpAssumption", Constants.TEST_SKIP, testTags)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSuiteSetUpAssumption", "test_succeed", Constants.TEST_SKIP, testTags, null, true)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Assumption failed: assumption is not true"]
  }

  def "test assumption failure in a multi-test-case suite"() {
    setup:
    runTestClasses(TestAssumptionAndSucceed)

    expect:
    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, Constants.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestAssumptionAndSucceed", Constants.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumptionAndSucceed", "test_fail_assumption", Constants.TEST_SKIP, testTags)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestAssumptionAndSucceed", "test_succeed", Constants.TEST_PASS)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Assumption failed: assumption is not true"]
  }

  def "test multiple successful suites"() {
    setup:
    runTestClasses(TestSucceed, TestSucceedAndSkipped)

    expect:
    assertTraces(1) {
      trace(6, true) {
        long testModuleId = testModuleSpan(it, 3, Constants.TEST_PASS)

        long firstSuiteId = testSuiteSpan(it, 4, testModuleId, "org.example.TestSucceed", Constants.TEST_PASS)
        testSpan(it, 0, testModuleId, firstSuiteId, "org.example.TestSucceed", "test_succeed", Constants.TEST_PASS)

        long secondSuiteId = testSuiteSpan(it, 5, testModuleId, "org.example.TestSucceedAndSkipped", Constants.TEST_PASS)
        testSpan(it, 1, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", Constants.TEST_SKIP, testTags, null, true)
        testSpan(it, 2, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_succeed", Constants.TEST_PASS)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test successful suite and failing suite"() {
    setup:
    runTestClasses(TestSucceed, TestFailedAndSucceed)

    expect:
    assertTraces(1) {
      trace(7, true) {
        long testModuleId = testModuleSpan(it, 4, Constants.TEST_FAIL)

        long firstSuiteId = testSuiteSpan(it, 6, testModuleId, "org.example.TestSucceed", Constants.TEST_PASS)
        testSpan(it, 3, testModuleId, firstSuiteId, "org.example.TestSucceed", "test_succeed", Constants.TEST_PASS)

        long secondSuiteId = testSuiteSpan(it, 5, testModuleId, "org.example.TestFailedAndSucceed", Constants.TEST_FAIL)
        testSpan(it, 2, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", Constants.TEST_PASS)
        testSpan(it, 1, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_failed", Constants.TEST_FAIL, null, exception)
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", Constants.TEST_PASS)
      }
    }

    where:
    exception = new AssertionFailedError("expected: <true> but was: <false>")
  }

  def "test nested successful suites"() {
    setup:
    runTestClasses(TestSucceedNested)

    expect:
    assertTraces(1) {
      trace(5, true) {
        long testModuleId = testModuleSpan(it, 2, Constants.TEST_PASS)

        long topLevelSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestSucceedNested", Constants.TEST_PASS)
        testSpan(it, 1, testModuleId, topLevelSuiteId, "org.example.TestSucceedNested", "test_succeed", Constants.TEST_PASS)

        long nestedSuiteId = testSuiteSpan(it, 4, testModuleId, 'org.example.TestSucceedNested$NestedSuite', Constants.TEST_PASS)
        testSpan(it, 0, testModuleId, nestedSuiteId, 'org.example.TestSucceedNested$NestedSuite', "test_succeed_nested", Constants.TEST_PASS)
      }
    }
  }

  def "test nested skipped suites"() {
    setup:
    runTestClasses(TestSkippedNested)

    expect:
    assertTraces(1) {
      trace(5, true) {
        long testModuleId = testModuleSpan(it, 2, Constants.TEST_SKIP)

        long topLevelSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestSkippedNested", Constants.TEST_SKIP, testTags)
        testSpan(it, 1, testModuleId, topLevelSuiteId, "org.example.TestSkippedNested", "test_succeed", Constants.TEST_SKIP, testTags, null, true)

        long nestedSuiteId = testSuiteSpan(it, 4, testModuleId, 'org.example.TestSkippedNested$NestedSuite', Constants.TEST_SKIP, testTags)
        testSpan(it, 0, testModuleId, nestedSuiteId, 'org.example.TestSkippedNested$NestedSuite', "test_succeed_nested", Constants.TEST_SKIP, testTags, null, true)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in class"]
  }

  private static void runTestClasses(Class<?>... classes) {
    DiscoverySelector[] selectors = new DiscoverySelector[classes.length]
    for (i in 0..<classes.length) {
      selectors[i] = selectClass(classes[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectors)
      .build()

    def launcher = LauncherFactory.create()
    launcher.execute(launcherReq)
  }

  private static void runTestClassesSuppressingExceptions(Class<?>... classes) {
    try {
      runTestClasses(classes)
    } catch (Throwable ignored) {
    }
  }

  @Override
  String expectedOperationPrefix() {
    return "junit"
  }

  @Override
  String expectedTestFramework() {
    return JUnit5Decorator.DECORATE.testFramework()
  }

  @Override
  String expectedTestFrameworkVersion() {
    return "5.8.2"
  }

  @Override
  String component() {
    return JUnit5Decorator.DECORATE.component()
  }
}
