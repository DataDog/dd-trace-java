import datadog.trace.agent.test.base.TestFrameworkTest
import datadog.trace.api.DisableTestTrace
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator
import datadog.trace.instrumentation.junit4.JUnit4Decorator
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
      trace(0, 1) {
        testSpan(it, 0, "TestSucceed", "test_succeed", TestDecorator.TEST_PASS, null)
      }
    }
  }

  def "test inheritance generates spans"() {
    setup:
    runner.run(TestInheritance)

    expect:
    assertTraces(1) {
      trace(0, 1) {
        testSpan(it, 0, "TestInheritance", "test_succeed", TestDecorator.TEST_PASS, null)
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
      trace(0, 1) {
        testSpan(it, 0, "TestFailed", "test_failed", TestDecorator.TEST_FAIL, exception)
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
      trace(0, 1) {
        testSpan(it, 0, "TestError", "test_error", TestDecorator.TEST_FAIL, exception)
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
      trace(0, 1) {
        testSpan(it, 0, "TestSkipped", "test_skipped", TestDecorator.TEST_SKIP, null)
      }
    }
  }

  def "test class skipped generated spans"() {
    setup:
    runner.run(TestSkippedClass)

    expect:
    assertTraces(1) {
      trace(0, 1) {
        testSpan(it, 0, "TestSkippedClass", "test_class_skipped", TestDecorator.TEST_SKIP, null)
      }
    }
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
}
