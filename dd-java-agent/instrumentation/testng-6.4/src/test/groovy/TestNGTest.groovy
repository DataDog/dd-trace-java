import datadog.trace.agent.test.base.TestFrameworkTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator
import datadog.trace.instrumentation.testng.TestNGDecorator
import org.example.TestError
import org.example.TestFailed
import org.example.TestFailedWithSuccessPercentage
import org.example.TestInheritance
import org.example.TestParameterized
import org.example.TestSkipped
import org.example.TestSucceed
import org.testng.TestNG

class TestNGTest extends TestFrameworkTest {

  static testOutputDir = "build/tmp/test-output"

  def "test success generates spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestSucceed)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    assertTraces(1) {
      trace(1) {
        testSpan(it, 0, "org.example.TestSucceed", "test_succeed", TestDecorator.TEST_PASS)
      }
    }
  }

  def "test inheritance generates spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestInheritance)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    assertTraces(1) {
      trace(1) {
        testSpan(it, 0, "org.example.TestInheritance", "test_succeed", TestDecorator.TEST_PASS)
      }
    }
  }

  def "test failed generates spans"() {
    setup:
    try {
      def testNG = new TestNG()
      testNG.setTestClasses(TestFailed)
      testNG.setOutputDirectory(testOutputDir)
      testNG.run()
    } catch (Throwable ignored) {
      // Ignored
    }

    expect:
    assertTraces(1) {
      trace(1) {
        testSpan(it, 0, "org.example.TestFailed", "test_failed", TestDecorator.TEST_FAIL, null, exception)
      }
    }

    where:
    exception = new AssertionError("expected:<true> but was:<false>", null)
  }

  def "test failed with success percentage generates spans"() {
    setup:
    try {
      def testNG = new TestNG()
      testNG.setTestClasses(TestFailedWithSuccessPercentage)
      testNG.setOutputDirectory(testOutputDir)
      testNG.run()
    } catch (Throwable ignored) {
      // Ignored
    }

    expect:
    assertTraces(5) {
      trace(1) {
        testSpan(it, 0, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", TestDecorator.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", TestDecorator.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", TestDecorator.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", TestDecorator.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", TestDecorator.TEST_PASS)
      }
    }

    where:
    exception = new AssertionError("expected:<true> but was:<false>", null)
  }

  def "test error generates spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestError)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    assertTraces(1) {
      trace(1) {
        testSpan(it, 0, "org.example.TestError", "test_error", TestDecorator.TEST_FAIL, null, exception)
      }
    }

    where:
    exception = new IllegalArgumentException("This exception is an example")
  }

  def "test skipped generates spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestSkipped)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    assertTraces(1) {
      trace(1) {
        testSpan(it, 0, "org.example.TestSkipped", "test_skipped", TestDecorator.TEST_SKIP, testTags, null, true)
      }
    }

    where:
    testTags = ["$Tags.TEST_SKIP_REASON": "Ignore reason in test"]
  }

  def "test parameterized generates spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestParameterized)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    assertTraces(2) {
      trace(1) {
        testSpan(it, 0, "org.example.TestParameterized", "parameterized_test_succeed", TestDecorator.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, "org.example.TestParameterized", "parameterized_test_succeed", TestDecorator.TEST_PASS, testTags_1)
      }
    }

    where:
    testTags_0 = ["$Tags.TEST_PARAMETERS": '{"arguments":{"0":"hello","1":"true"}}']
    testTags_1 = ["$Tags.TEST_PARAMETERS": '{"arguments":{"0":"\\\"goodbye\\\"","1":"false"}}']
  }

  @Override
  String expectedOperationName() {
    return "testng.test"
  }

  @Override
  String expectedTestFramework() {
    return TestNGDecorator.DECORATE.testFramework()
  }

  @Override
  String expectedTestFrameworkVersion() {
    // For the TestNG version used for tests (minimum supported)
    // it's not possible to extract the version.
    return null
  }

  @Override
  String component() {
    return TestNGDecorator.DECORATE.component()
  }

  @Override
  boolean isCI() {
    return TestNGDecorator.DECORATE.isCI()
  }

  @Override
  Map<String, String> ciTags() {
    return TestNGDecorator.DECORATE.getCiTags()
  }
}
