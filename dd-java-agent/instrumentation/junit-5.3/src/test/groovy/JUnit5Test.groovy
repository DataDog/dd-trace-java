import datadog.trace.agent.test.base.TestFrameworkTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator
import datadog.trace.instrumentation.junit5.JUnit5Decorator
import org.example.TestError
import org.example.TestFactory
import org.example.TestFailed
import org.example.TestInheritance
import org.example.TestParameterized
import org.example.TestRepeated
import org.example.TestSkipped
import org.example.TestSkippedClass
import org.example.TestSucceed
import org.example.TestTemplate
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.opentest4j.AssertionFailedError

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

class JUnit5Test extends TestFrameworkTest {

  def "test success generate spans"() {
    setup:
    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectClass(TestSucceed)).build()
    def launcher = LauncherFactory.create()
    launcher.execute(launcherReq)

    expect:
    assertTraces(1) {
      trace(1) {
        testSpan(it, 0, "org.example.TestSucceed", "test_succeed", TestDecorator.TEST_PASS)
      }
    }
  }

  def "test inheritance generates spans"() {
    setup:
    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectClass(TestInheritance)).build()
    def launcher = LauncherFactory.create()
    launcher.execute(launcherReq)

    expect:
    assertTraces(1) {
      trace(1) {
        testSpan(it, 0, "org.example.TestInheritance", "test_succeed", TestDecorator.TEST_PASS)
      }
    }
  }

  def "test parameterized generates spans"() {
    setup:
    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectClass(TestParameterized)).build()
    def launcher = LauncherFactory.create()
    launcher.execute(launcherReq)

    expect:
    assertTraces(2) {
      trace(1) {
        testSpan(it, 0, "org.example.TestParameterized", "test_parameterized", TestDecorator.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, "org.example.TestParameterized", "test_parameterized", TestDecorator.TEST_PASS)
      }
    }
  }

  def "test repeated generates spans"() {
    setup:
    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectClass(TestRepeated)).build()
    def launcher = LauncherFactory.create()
    launcher.execute(launcherReq)

    expect:
    assertTraces(2) {
      trace(1) {
        testSpan(it, 0, "org.example.TestRepeated", "test_repeated", TestDecorator.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, "org.example.TestRepeated", "test_repeated", TestDecorator.TEST_PASS)
      }
    }
  }

  def "test template generates spans"() {
    setup:
    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectClass(TestTemplate)).build()
    def launcher = LauncherFactory.create()
    launcher.execute(launcherReq)

    expect:
    assertTraces(2) {
      trace(1) {
        testSpan(it, 0, "org.example.TestTemplate", "test_template", TestDecorator.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, "org.example.TestTemplate", "test_template", TestDecorator.TEST_PASS)
      }
    }
  }

  def "test factory generates spans"() {
    setup:
    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectClass(TestFactory)).build()
    def launcher = LauncherFactory.create()
    launcher.execute(launcherReq)

    expect:
    assertTraces(2) {
      trace(1) {
        testSpan(it, 0, "org.example.TestFactory", "test_factory", TestDecorator.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, "org.example.TestFactory", "test_factory", TestDecorator.TEST_PASS)
      }
    }

  }

  def "test failed generates spans"() {
    setup:
    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectClass(TestFailed)).build()
    def launcher = LauncherFactory.create()

    try {
      launcher.execute(launcherReq)
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
    exception = new AssertionFailedError("expected: <true> but was: <false>")
  }

  def "test error generates spans"() {
    setup:
    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectClass(TestError)).build()
    def launcher = LauncherFactory.create()

    try {
      launcher.execute(launcherReq)
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
    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectClass(TestSkipped)).build()
    def launcher = LauncherFactory.create()
    launcher.execute(launcherReq)

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
    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectClass(TestSkippedClass)).build()
    def launcher = LauncherFactory.create()
    launcher.execute(launcherReq)

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
    return JUnit5Decorator.DECORATE.testFramework()
  }

  @Override
  String component() {
    return JUnit5Decorator.DECORATE.component()
  }

  @Override
  boolean isCI() {
    return JUnit5Decorator.DECORATE.isCI()
  }

  @Override
  Map<String, String> ciTags() {
    return JUnit5Decorator.DECORATE.getCiTags()
  }
}
