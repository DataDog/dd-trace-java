import datadog.trace.agent.test.base.TestFrameworkTest
import datadog.trace.api.DisableTestTrace
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator
import datadog.trace.instrumentation.junit4.JUnit4Decorator
import org.example.TestError
import org.example.TestFailed
import org.example.TestInheritance
import org.example.TestSkipped
import org.example.TestSkippedClass
import org.example.TestSucceed
import org.junit.runner.JUnitCore
import spock.lang.Shared

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit4Test extends TestFrameworkTest {

  @Shared
  def runner = new JUnitCore()

  def "test success generates spans"() {
    setup:
    runner.run(TestSucceed)

    expect:
    assertTraces(1) {
      trace(1) {
        testSpan(it, 0, "org.example.TestSucceed", "test_succeed", TestDecorator.TEST_PASS)
      }
    }
  }

  def "test inheritance generates spans"() {
    setup:
    runner.run(TestInheritance)

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
      runner.run(TestFailed)
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
    exception = new AssertionError()
  }

  def "test error generates spans"() {
    setup:
    try {
      runner.run(TestError)
    } catch (Throwable ignored) {
      // Ignored
    }

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
    runner.run(TestSkipped)

    expect:
    assertTraces(1) {
      trace(1) {
        testSpan(it, 0, "org.example.TestSkipped", "test_skipped", TestDecorator.TEST_SKIP, testTags)
      }
    }

    where:
    testTags = ["$Tags.TEST_SKIP_REASON": "Ignore reason in test"]
  }

  def "test class skipped generated spans"() {
    setup:
    runner.run(TestSkippedClass)

    expect:
    assertTraces(1) {
      trace(1) {
        testSpan(it, 0, "org.example.TestSkippedClass", "test_class_skipped", TestDecorator.TEST_SKIP, testTags)
      }
    }

    where:
    testTags = ["$Tags.TEST_SKIP_REASON": "Ignore reason in class"]
  }

  @Override
  String expectedOperationName() {
    return "junit.test"
  }

  @Override
  String expectedTestFramework() {
    return JUnit4Decorator.DECORATE.testFramework()
  }

  @Override
  String component() {
    return JUnit4Decorator.DECORATE.component()
  }

  @Override
  boolean isCI() {
    return JUnit4Decorator.DECORATE.isCI()
  }

  @Override
  Map<String, String> ciTags() {
    return JUnit4Decorator.DECORATE.getCiTags()
  }
}
