import com.google.common.reflect.ClassPath
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.SpockRunner
import datadog.trace.agent.test.utils.ClasspathUtils
import datadog.trace.api.GlobalTracer
import datadog.trace.bootstrap.Constants
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import spock.lang.Shared

import java.util.concurrent.TimeoutException

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE

class AgentTestRunnerTest extends AgentTestRunner {
  private static final ClassLoader BOOTSTRAP_CLASSLOADER = null

  @Shared
  private Class sharedSpanClass

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(TRACE_CLASSES_EXCLUDE, "config.exclude.packagename.*, config.exclude.SomeClass,config.exclude.SomeClass\$NestedClass")
  }

  def setupSpec() {
    sharedSpanClass = AgentSpan
  }

  def "spock runner bootstrap prefixes correct for test setup"() {
    expect:
    SpockRunner.BOOTSTRAP_PACKAGE_PREFIXES_COPY == Constants.BOOTSTRAP_PACKAGE_PREFIXES
  }

  def "classpath setup"() {
    setup:
    final List<String> bootstrapClassesIncorrectlyLoaded = []
    for (ClassPath.ClassInfo info : ClasspathUtils.getTestClasspath().getAllClasses()) {
      for (int i = 0; i < Constants.BOOTSTRAP_PACKAGE_PREFIXES.length; ++i) {
        if (info.getName().startsWith(Constants.BOOTSTRAP_PACKAGE_PREFIXES[i])) {
          Class<?> bootstrapClass = Class.forName(info.getName())
          if (bootstrapClass.getClassLoader() != BOOTSTRAP_CLASSLOADER) {
            bootstrapClassesIncorrectlyLoaded.add(bootstrapClass)
          }
          break
        }
      }
    }

    expect:
    sharedSpanClass.getClassLoader() == BOOTSTRAP_CLASSLOADER
    AgentTracer.getClassLoader() == BOOTSTRAP_CLASSLOADER
    TEST_TRACER == AgentTracer.get()
    AgentTracer.get() == GlobalTracer.get()
    bootstrapClassesIncorrectlyLoaded == []
  }

  def "waiting for child spans times out"() {
    when:
    runUnderTrace("parent") {
      blockUntilChildSpansFinished(1)
    }

    then:
    thrown(TimeoutException)
  }

  def "waiting for noop span returns immediately"() {
    when:
    AgentScope scope
    runUnderTrace("parent") {
      scope = TEST_TRACER.activateSpan(AgentTracer.NoopAgentSpan.INSTANCE, ScopeSource.INSTRUMENTATION)

      blockUntilChildSpansFinished(1)
    }

    then:
    noExceptionThrown()

    cleanup:
    scope?.close()
  }

  def "logging works"() {
    when:
    org.slf4j.LoggerFactory.getLogger(AgentTestRunnerTest).debug("hello")
    then:
    noExceptionThrown()
  }

  def "excluded classes are not instrumented"() {
    when:
    runUnderTrace("parent") {
      subject.run()
    }

    then:
    !TRANSFORMED_CLASSES_NAMES.contains(subject.class.name)
    assertTraces(1) {
      trace(1) {
        span {
          operationName "parent"
        }
      }
    }

    where:
    subject                                                | _
    new config.exclude.SomeClass()                         | _
    new config.exclude.SomeClass.NestedClass()             | _
    new config.exclude.packagename.SomeClass()             | _
    new config.exclude.packagename.SomeClass.NestedClass() | _
  }

  def "test unblocked by completed span"() {
    setup:
    runUnderTrace("parent") {
      runUnderTrace("child") {}
      blockUntilChildSpansFinished(1)
    }

    expect:
    assertTraces(1) {
      trace(2) {
        span {
          operationName "parent"
          parent()
        }
        span {
          operationName "child"
          childOf(span(0))
        }
      }
    }
  }
}
