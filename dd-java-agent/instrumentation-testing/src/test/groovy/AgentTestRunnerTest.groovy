import com.google.common.reflect.ClassPath
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.BootstrapClasspathSetupListener
import datadog.trace.api.GlobalTracer
import datadog.trace.api.Platform
import datadog.trace.bootstrap.Constants
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import spock.lang.Shared

import java.util.concurrent.TimeoutException

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan

class AgentTestRunnerTest extends InstrumentationSpecification {
  private static final ClassLoader BOOTSTRAP_CLASSLOADER = null
  private static final BigDecimal JAVA_VERSION = new BigDecimal(System.getProperty("java.specification.version"))
  private static final boolean IS_AT_LEAST_JAVA_17 = JAVA_VERSION.isAtLeast(17.0)

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
    BootstrapClasspathSetupListener.BOOTSTRAP_PACKAGE_PREFIXES_COPY == Constants.BOOTSTRAP_PACKAGE_PREFIXES
  }

  def "classpath setup"() {
    setup:
    boolean jfrSupported = isJFRSupported()
    final List<String> bootstrapClassesIncorrectlyLoaded = []
    for (ClassPath.ClassInfo info : BootstrapClasspathSetupListener.TEST_CLASSPATH.getAllClasses()) {
      for (int i = 0; i < Constants.BOOTSTRAP_PACKAGE_PREFIXES.length; ++i) {
        if (info.getName().startsWith(Constants.BOOTSTRAP_PACKAGE_PREFIXES[i])) {
          if (!jfrSupported && info.name.startsWith("datadog.trace.bootstrap.instrumentation.jfr.")) {
            continue // skip exception-profiling classes - they won't load if JFR is not available
          }
          try {
            Class<?> bootstrapClass = Class.forName(info.getName())
            if (bootstrapClass.getClassLoader() != BOOTSTRAP_CLASSLOADER) {
              bootstrapClassesIncorrectlyLoaded.add(bootstrapClass)
            }
            break
          } catch (UnsupportedClassVersionError e) {
            // A dirty hack to allow passing this test on Java 7
            if (info.getName().startsWith("datadog.trace.api.sampling.")) {
              // The rate limiting sampler support is consciously compiled to Java 8 bytecode
              // The sampler will not be used unless JFR is available -> running on Java 8+
              // Simply ignore the error as the class will not be even attempted to get loaded on Java 7
              break
            }
            if (info.getName().startsWith("datadog.trace.util.stacktrace.")) {
              //It is known that support for Java 7 is going to be discontinued
              //so we have decided to implement everything related to IAST in java8
              break
            }
            // rethrow the exception otherwise
            throw e
          } catch (IllegalAccessError e) {
            // A dirty hack to allow passing this test on Java 17
            if (IS_AT_LEAST_JAVA_17 && info.getName() == "datadog.trace.bootstrap.instrumentation.rmi.ContextDispatcher") {
              // The ContextDispatcher class implements a public interface sun.rmi.server.Dispatcher which
              // is in a module that is not open under Java 17+
              break
            }
            // rethrow the exception otherwise
            throw e
          } catch (NoClassDefFoundError e) {
            // A dirty hack to allow passing this test on Java 7
            if (info.getName() == "sun.misc.SharedSecrets") {
              //datadog.trace.util.stacktrace.HotSpotStackWalker uses sun.misc.SharedSecrets to improve performance in jdk8 with hotspot
              break
            }
            // rethrow the exception otherwise
          }
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
      scope = TEST_TRACER.activateManualSpan(noopSpan())

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

  def "excluded classes are not instrumented #iterationIndex"() {
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

  boolean isJFRSupported() {
    return Platform.hasJfr()
  }
}
