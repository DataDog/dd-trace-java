import com.google.common.reflect.ClassPath
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.SpockRunner
import datadog.trace.agent.test.utils.ClasspathUtils
import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.agent.tooling.Constants
import datadog.trace.api.GlobalTracer
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import spock.lang.Shared

import java.lang.reflect.Field
import java.util.concurrent.TimeoutException

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.Config.TRACE_CLASSES_EXCLUDE

class AgentTestRunnerTest extends AgentTestRunner {
  private static final ClassLoader BOOTSTRAP_CLASSLOADER = null
  private static final boolean AGENT_INSTALLED_IN_CLINIT

  @Shared
  private Class sharedSpanClass

  static {
    ConfigUtils.updateConfig {
      System.setProperty("dd." + TRACE_CLASSES_EXCLUDE, "config.exclude.packagename.*, config.exclude.SomeClass,config.exclude.SomeClass\$NestedClass")
    }

    AGENT_INSTALLED_IN_CLINIT = getAgentTransformer() != null
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
    // shared OT classes should cause no trouble
    sharedSpanClass.getClassLoader() == BOOTSTRAP_CLASSLOADER
    AgentTracer.getClassLoader() == BOOTSTRAP_CLASSLOADER
    !AGENT_INSTALLED_IN_CLINIT
    getTestTracer() == AgentTracer.get()
    getAgentTransformer() != null
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
      trace(0, 1) {
        span(0) {
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
      trace(0, 2) {
        span(0) {
          operationName "parent"
          parent()
        }
        span(1) {
          operationName "child"
          childOf(span(0))
        }
      }
    }
  }

  private static getAgentTransformer() {
    Field f
    try {
      f = AgentTestRunner.getDeclaredField("activeTransformer")
      f.setAccessible(true)
      return f.get(null)
    } finally {
      f.setAccessible(false)
    }
  }
}
