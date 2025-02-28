import com.intuit.karate.FileUtils
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.CiVisibilityTestUtils
import datadog.trace.civisibility.config.LibraryCapabilityUtils
import datadog.trace.instrumentation.karate.KarateUtils
import datadog.trace.instrumentation.karate.TestEventsHandlerHolder
import org.example.*
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class KarateTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    Assumptions.assumeTrue(assumption)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName            | success | tests                          | assumption
    "test-succeed"          | true    | [TestSucceedKarate]            | true
    "test-succeed-parallel" | true    | [TestSucceedParallelKarate]    | true
    "test-with-setup"       | true    | [TestWithSetupKarate]          | KarateUtils.isSetupTagSupported(FileUtils.KARATE_VERSION)
    "test-parameterized"    | true    | [TestParameterizedKarate]      | true
    "test-failed"           | false   | [TestFailedKarate]             | true
    "test-skipped-feature"  | true    | [TestSkippedFeatureKarate]     | true
    "test-built-in-retry"   | true    | [TestFailedBuiltInRetryKarate] | true
  }

  def "test ITR #testcaseName"() {
    Assumptions.assumeTrue(KarateUtils.isSkippingSupported(FileUtils.KARATE_VERSION))

    givenSkippableTests(skippedTests)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                      | tests                     | skippedTests
    "test-itr-skipping"               | [TestSucceedKarate]       | [new TestIdentifier("[org/example/test_succeed] test succeed", "first scenario", null)]
    "test-itr-skipping-parameterized" | [TestParameterizedKarate] | [
      new TestIdentifier("[org/example/test_parameterized] test parameterized", "first scenario as an outline", '{"param":"\'a\'","value":"aa"}')
    ]
    "test-itr-unskippable"            | [TestUnskippableKarate]   | [new TestIdentifier("[org/example/test_unskippable] test unskippable", "first scenario", null)]
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName               | success | tests                           | retriedTests
    "test-failed"              | false   | [TestFailedKarate]              | []
    "test-retry-failed"        | false   | [TestFailedKarate]              | [new TestFQN("[org/example/test_failed] test failed", "second scenario")]
    "test-failed-then-succeed" | true    | [TestFailedThenSucceedKarate]   | [new TestFQN("[org/example/test_failed_then_succeed] test failed", "flaky scenario")]
    "test-retry-parameterized" | false   | [TestFailedParameterizedKarate] | [
      new TestFQN("[org/example/test_failed_parameterized] test parameterized", "first scenario as an outline")
    ]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | tests                              | knownTestsList
    "test-efd-known-test"               | [TestSucceedOneCaseKarate]         | [new TestFQN("[org/example/test_succeed_one_case] test succeed", "first scenario")]
    "test-efd-known-parameterized-test" | [TestParameterizedKarate]          | [
      new TestFQN("[org/example/test_parameterized] test parameterized", "first scenario as an outline")
    ]
    "test-efd-new-test"                 | [TestSucceedOneCaseKarate]         | []
    "test-efd-new-parameterized-test"   | [TestParameterizedKarate]          | []
    "test-efd-new-slow-test"            | [TestSucceedKarateSlow]            | [] // is executed only twice
    "test-efd-faulty-session-threshold" | [TestParameterizedMoreCasesKarate] | []
  }

  def "test quarantined #testcaseName"() {
    givenQuarantinedTests(quarantined)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName              | tests              | quarantined
    "test-quarantined-failed" | [TestFailedKarate] | [new TestFQN("[org/example/test_failed] test failed", "second scenario")]
  }

  def "test quarantined auto-retries #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenFlakyRetryEnabled(true)
    givenFlakyTests(retried)

    // every test retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                  | tests              | quarantined                                                               | retried
    "test-quarantined-failed-atr" | [TestFailedKarate] | [new TestFQN("[org/example/test_failed] test failed", "second scenario")] | [new TestFQN("[org/example/test_failed] test failed", "second scenario")]
  }

  def "test quarantined early flakiness detection #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(known)

    // every test retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                    | tests              | quarantined                                                               | known
    "test-quarantined-failed-known" | [TestFailedKarate] | [new TestFQN("[org/example/test_failed] test failed", "second scenario")] | [new TestFQN("[org/example/test_failed] test failed", "second scenario")]
    "test-quarantined-failed-efd"   | [TestFailedKarate] | [new TestFQN("[org/example/test_failed] test failed", "second scenario")] | []
  }

  def "test disabled #testcaseName"() {
    Assumptions.assumeTrue(KarateUtils.isSkippingSupported(FileUtils.KARATE_VERSION))

    givenDisabledTests(disabled)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName           | tests              | disabled
    "test-disabled-failed" | [TestFailedKarate] | [new TestFQN("[org/example/test_failed] test failed", "second scenario")]
  }

  def "test attempt to fix #testcaseName"() {
    givenQuarantinedTests(quarantined)
    givenDisabledTests(disabled)
    givenAttemptToFixTests(attemptToFix)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                | success | tests               | attemptToFix                                                               | quarantined                                                                | disabled
    "test-attempt-to-fix-failed"                | false   | [TestFailedKarate]  | [new TestFQN("[org/example/test_failed] test failed", "second scenario")]  | []                                                                         | []
    "test-attempt-to-fix-succeeded"             | true    | [TestSucceedKarate] | [new TestFQN("[org/example/test_succeed] test succeed", "first scenario")] | []                                                                         | []
    "test-attempt-to-fix-quarantined-failed"    | true    | [TestFailedKarate]  | [new TestFQN("[org/example/test_failed] test failed", "second scenario")]  | [new TestFQN("[org/example/test_failed] test failed", "second scenario")]  | []
    "test-attempt-to-fix-quarantined-succeeded" | true    | [TestSucceedKarate] | [new TestFQN("[org/example/test_succeed] test succeed", "first scenario")] | [new TestFQN("[org/example/test_succeed] test succeed", "first scenario")] | []
    "test-attempt-to-fix-disabled-failed"       | true    | [TestFailedKarate]  | [new TestFQN("[org/example/test_failed] test failed", "second scenario")]  | []                                                                         | [new TestFQN("[org/example/test_failed] test failed", "second scenario")]
    "test-attempt-to-fix-disabled-succeeded"    | true    | [TestSucceedKarate] | [new TestFQN("[org/example/test_succeed] test succeed", "first scenario")] | []                                                                         | [new TestFQN("[org/example/test_succeed] test succeed", "first scenario")]
  }

  def "test capabilities tagging #testcaseName"() {
    Assumptions.assumeTrue(assumption)

    def notPresentTags = new HashSet<>(LibraryCapabilityUtils.CAPABILITY_TAG_MAP.values())
    notPresentTags.removeAll(presentTags)

    runTests([TestSucceedOneCaseKarate], true)

    ListWriterAssert.assertTraces(TEST_WRITER, 5, true, new CiVisibilityTestUtils.SortTracesByType(), {
      trace(1) {
        span(0) {
          spanType DDSpanTypes.TEST_SESSION_END
          tags(false) {
            arePresent(presentTags)
            areNotPresent(notPresentTags)
          }
        }
      }
    })

    where:
    testcaseName                 | presentTags                                                                                                                                                                                                                   | assumption
    "test-capabilities-base"     | [
      DDTags.LIBRARY_CAPABILITIES_ATR,
      DDTags.LIBRARY_CAPABILITIES_EFD,
      DDTags.LIBRARY_CAPABILITIES_QUARANTINE,
      DDTags.LIBRARY_CAPABILITIES_ATTEMPT_TO_FIX
    ]                                                                        | !KarateUtils.isSkippingSupported(FileUtils.KARATE_VERSION)
    "test-capabilities-skipping" | [
      DDTags.LIBRARY_CAPABILITIES_ATR,
      DDTags.LIBRARY_CAPABILITIES_EFD,
      DDTags.LIBRARY_CAPABILITIES_QUARANTINE,
      DDTags.LIBRARY_CAPABILITIES_ATTEMPT_TO_FIX,
      DDTags.LIBRARY_CAPABILITIES_TIA,
      DDTags.LIBRARY_CAPABILITIES_DISABLED
    ] | KarateUtils.isSkippingSupported(FileUtils.KARATE_VERSION)
  }

  private void runTests(List<Class<?>> tests, boolean expectSuccess = true) {
    TestEventsHandlerHolder.start()

    DiscoverySelector[] selectors = new DiscoverySelector[tests.size()]
    for (i in 0..<tests.size()) {
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
    def listener = new TestResultListener()
    launcher.registerTestExecutionListeners(listener)
    try {
      launcher.execute(launcherReq)

      def failedTests = listener.testsByStatus[TestExecutionResult.Status.FAILED]
      if (expectSuccess) {
        if (failedTests != null && !failedTests.isEmpty()) {
          throw new AssertionError("Expected successful execution, the following tests were reported as failed: " + failedTests)
        }
      } else {
        if (failedTests == null || failedTests.isEmpty()) {
          throw new AssertionError("Expected a failed execution, got no failed tests")
        }
      }
    } finally {
      TestEventsHandlerHolder.stop()
    }
  }

  @Override
  String instrumentedLibraryName() {
    return "karate"
  }

  @Override
  String instrumentedLibraryVersion() {
    return FileUtils.KARATE_VERSION
  }

  private static final class TestResultListener implements TestExecutionListener {
    private final Map<TestExecutionResult.Status, Collection<org.junit.platform.launcher.TestIdentifier>> testsByStatus = new ConcurrentHashMap<>()

    void executionFinished(org.junit.platform.launcher.TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
      testsByStatus.computeIfAbsent(testExecutionResult.status, k -> new CopyOnWriteArrayList<>()).add(testIdentifier)
    }
  }
}
