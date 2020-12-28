package datadog.trace.agent.test

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.util.ContextInitializer
import com.google.common.collect.Sets
import com.timgroup.statsd.StatsDClient
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.tooling.AgentInstaller
import datadog.trace.agent.tooling.Instrumenter
import datadog.trace.agent.tooling.TracerInstaller
import datadog.trace.api.Config
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.PendingTrace
import datadog.trace.test.util.DDSpecification
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.utility.JavaModule
import org.junit.runner.RunWith
import org.slf4j.LoggerFactory
import org.spockframework.mock.MockUtil
import spock.lang.Shared

import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.tooling.bytebuddy.matcher.AdditionalLibraryIgnoresMatcher.additionalLibraryIgnoresMatcher
import static datadog.trace.api.IdGenerationStrategy.THREAD_PREFIX

/**
 * A spock test runner which automatically applies instrumentation and exposes a global trace
 * writer.
 *
 * <p>To use, write a regular spock test, but extend this class instead of {@link
 * spock.lang.Specification}. <br>
 * This will cause the following to occur before test startup:
 *
 * <ul>
 *   <li>All {@link Instrumenter}s on the test classpath will be applied. Matching preloaded classes
 *       will be retransformed.
 *   <li>{@link AgentTestRunner#TEST_WRITER} will be registered with the global tracer and available
 *       in an initialized state.
 * </ul>
 */
// CodeNarc incorrectly thinks ".class" is unnecessary in @RunWith
@SuppressWarnings('UnnecessaryDotClass')
@RunWith(SpockRunner.class)
abstract class AgentTestRunner extends DDSpecification implements AgentBuilder.Listener {
  private static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10)

  protected static final Instrumentation INSTRUMENTATION = ByteBuddyAgent.getInstrumentation()

  static {
    configureLoggingLevels()
  }

  /**
   * For test runs, agent's global tracer will report to this list writer.
   *
   * <p>Before the start of each test the reported traces will be reset.
   */
  @SuppressWarnings('PropertyName')
  @Shared
  ListWriter TEST_WRITER

  @SuppressWarnings('PropertyName')
  @Shared
  TracerAPI TEST_TRACER

  @SuppressWarnings('PropertyName')
  @Shared
  StatsDClient STATS_D_CLIENT = Mock(StatsDClient)

  @SuppressWarnings('PropertyName')
  @Shared
  Set<String> TRANSFORMED_CLASSES_NAMES = Sets.newConcurrentHashSet()

  @SuppressWarnings('PropertyName')
  @Shared
  Set<TypeDescription> TRANSFORMED_CLASSES_TYPES = Sets.newConcurrentHashSet()

  @SuppressWarnings('PropertyName')
  @Shared
  AtomicInteger INSTRUMENTATION_ERROR_COUNT = new AtomicInteger(0)

  @Shared
  ClassFileTransformer activeTransformer

  private static void configureLoggingLevels() {
    final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)
    if (!rootLogger.iteratorForAppenders().hasNext()) {
      try {
        // previous test wiped out the logging config bring it back for the next test
        new ContextInitializer(rootLogger.getLoggerContext()).autoConfig()
      } catch (final Exception e) {
        e.printStackTrace()
      }
    }

    rootLogger.setLevel(Level.WARN)
    ((Logger) LoggerFactory.getLogger("datadog")).setLevel(Level.DEBUG)
  }

  def setupSpec() {
    // If this fails, it's likely the result of another test loading Config before it can be
    // injected into the bootstrap classpath. If one test extends AgentTestRunner in a module, all tests must extend
    assert Config.getClassLoader() == null: "Config must load on the bootstrap classpath."

    configurePreAgent()

    TEST_WRITER = new ListWriter()
    TEST_TRACER =
      CoreTracer.builder()
        .writer(TEST_WRITER)
        .idGenerationStrategy(THREAD_PREFIX)
        .statsDClient(STATS_D_CLIENT)
        .build()
    TracerInstaller.forceInstallGlobalTracer(TEST_TRACER)

    assert ServiceLoader.load(Instrumenter, AgentTestRunner.getClassLoader())
      .iterator()
      .hasNext(): "No instrumentation found"
    activeTransformer = AgentInstaller.installBytebuddyAgent(INSTRUMENTATION, true, this)
  }

  /** Override to set config before the agent is installed */
  protected void configurePreAgent() {}

  def setup() {
    configureLoggingLevels()

    assert TEST_TRACER.activeSpan() == null: "Span is active before test has started: " + TEST_TRACER.activeSpan()

    // Config is reset before each test. Thus, configurePreAgent() has to be called before each test
    // even though the agent is already installed
    configurePreAgent()

    println "Starting test: ${getSpecificationContext().getCurrentIteration().getName()}"

    TEST_TRACER.flush()
    TEST_WRITER.start()

    new MockUtil().attachMock(STATS_D_CLIENT, this)
  }

  void cleanup() {
    TEST_TRACER.flush()
    new MockUtil().detachMock(STATS_D_CLIENT)
  }

  /** Override to clean up things after the agent is removed */
  protected void cleanupAfterAgent() {}

  def cleanupSpec() {
    TEST_TRACER?.close()

    if (null != activeTransformer) {
      INSTRUMENTATION.removeTransformer(activeTransformer)
      activeTransformer = null
    }

    cleanupAfterAgent()

    // All cleanup should happen before these assertion.  If not, a failing assertion may prevent cleanup
    assert INSTRUMENTATION_ERROR_COUNT.get() == 0: INSTRUMENTATION_ERROR_COUNT.get() + " Instrumentation errors during test"

    assert TRANSFORMED_CLASSES_TYPES.findAll { additionalLibraryIgnoresMatcher().matches(it) }.isEmpty(): "Transformed classes match global libraries ignore matcher"
  }

  void assertTraces(
    final int size,
    @ClosureParams(
      value = SimpleType,
      options = "datadog.trace.agent.test.asserts.ListWriterAssert")
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST)
    final Closure spec) {
    assertTraces(size, false, spec)
  }

  void assertTraces(
    final int size,
    final boolean ignoreAdditionalTraces,
    @ClosureParams(
      value = SimpleType,
      options = "datadog.trace.agent.test.asserts.ListWriterAssert")
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST)
    final Closure spec) {
    ListWriterAssert.assertTraces(TEST_WRITER, size, ignoreAdditionalTraces, spec)
  }

  void blockUntilChildSpansFinished(final int numberOfSpans) {
    final AgentSpan span = TEST_TRACER.activeSpan()

    blockUntilChildSpansFinished(span, numberOfSpans)
  }

  static void blockUntilChildSpansFinished(AgentSpan span, int numberOfSpans) {
    if (span instanceof DDSpan) {
      final PendingTrace pendingTrace = ((DDSpan) span).context().getTrace()

      final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS

      while (pendingTrace.size() < numberOfSpans) {
        if (System.currentTimeMillis() > deadline) {
          throw new TimeoutException(
            "Timed out waiting for child spans.  Received: " + pendingTrace.size())
        }
        Thread.sleep(10)
      }
    }
  }

  @Override
  void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamicType) {
    TRANSFORMED_CLASSES_NAMES.add(typeDescription.getActualName())
    TRANSFORMED_CLASSES_TYPES.add(typeDescription)
  }

  @Override
  void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
    // Intentionally prevented instrumentation
    if (throwable instanceof AbortTransformationException) {
      return
    }

    // Incorrect* classes assert on incorrect api usage. Error expected.
    if (typeName.startsWith('context.ContextTestInstrumentation$Incorrect') && throwable.getMessage().startsWith("Incorrect Context Api Usage detected.")) {
      return
    }

    println "Unexpected instrumentation error when instrumenting ${typeName} on ${classLoader}"
    throwable.printStackTrace()
    INSTRUMENTATION_ERROR_COUNT.incrementAndGet()
  }

  @Override
  void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
  }

  @Override
  void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {
  }

  @Override
  void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
  }
}

/** Used to signal that a transformation was intentionally aborted and is not an error. */
class AbortTransformationException extends RuntimeException {
  AbortTransformationException(final String message) {
    super(message)
  }
}
