package datadog.trace.agent.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.collect.Sets;
import datadog.opentracing.DDSpan;
import datadog.opentracing.DDTracer;
import datadog.trace.agent.tooling.AgentInstaller;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.Writer;
import io.opentracing.Tracer;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.slf4j.LoggerFactory;
import org.spockframework.runtime.model.SpecMetadata;
import spock.lang.Specification;

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
 *   <li>{@link AgentTestRunner#TEST_WRITER} will be registerd with the global tracer and available
 *       in an initialized state.
 * </ul>
 */
@RunWith(SpockRunner.class)
@SpecMetadata(filename = "AgentTestRunner.java", line = 0)
public abstract class AgentTestRunner extends Specification {
  /**
   * For test runs, agent's global tracer will report to this list writer.
   *
   * <p>Before the start of each test the reported traces will be reset.
   */
  public static final ListWriter TEST_WRITER;

  // having a reference to io.opentracing.Tracer in test field
  // loads opentracing before bootstrap classpath is setup
  // so we declare tracer as an object and cast when needed.
  protected static final Object TEST_TRACER;

  protected static final Set<String> TRANSFORMED_CLASSES = Sets.newConcurrentHashSet();
  private static final AtomicInteger INSTRUMENTATION_ERROR_COUNT = new AtomicInteger();
  private static final ErrorCountingListener ERROR_LISTENER = new ErrorCountingListener();

  private static final Instrumentation instrumentation;
  private static volatile ClassFileTransformer activeTransformer = null;

  static {
    instrumentation = ByteBuddyAgent.getInstrumentation();

    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
    ((Logger) LoggerFactory.getLogger("datadog")).setLevel(Level.DEBUG);

    TEST_WRITER =
        new ListWriter() {
          @Override
          public boolean add(final List<DDSpan> trace) {
            final boolean result = super.add(trace);
            return result;
          }
        };
    TEST_TRACER = new DDTracer(TEST_WRITER);
    TestUtils.registerOrReplaceGlobalTracer((Tracer) TEST_TRACER);
  }

  protected static Tracer getTestTracer() {
    return (Tracer) TEST_TRACER;
  }

  protected static Writer getTestWriter() {
    return TEST_WRITER;
  }

  /**
   * Invoked when Bytebuddy encounters an instrumentation error. Fails the test by default.
   *
   * <p>Override to skip specific expected errors.
   *
   * @return true if the test should fail because of this error.
   */
  protected boolean onInstrumentationError(
      final String typeName,
      final ClassLoader classLoader,
      final JavaModule module,
      final boolean loaded,
      final Throwable throwable) {
    System.err.println(
        "Unexpected instrumentation error when instrumenting " + typeName + " on " + classLoader);
    throwable.printStackTrace();
    return true;
  }

  @BeforeClass
  public static synchronized void agentSetup() throws Exception {
    if (null != activeTransformer) {
      throw new IllegalStateException("transformer already in place: " + activeTransformer);
    }

    final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(AgentTestRunner.class.getClassLoader());
      assert ServiceLoader.load(Instrumenter.class).iterator().hasNext();
      activeTransformer = AgentInstaller.installBytebuddyAgent(instrumentation, ERROR_LISTENER);
    } finally {
      Thread.currentThread().setContextClassLoader(contextLoader);
    }
  }

  @Before
  public void beforeTest() {
    TEST_WRITER.start();
    INSTRUMENTATION_ERROR_COUNT.set(0);
    ERROR_LISTENER.activateTest(this);
    assert getTestTracer().activeSpan() == null : "Span is active before test has started";
  }

  @After
  public void afterTest() {
    ERROR_LISTENER.deactivateTest(this);
    assert INSTRUMENTATION_ERROR_COUNT.get() == 0
        : INSTRUMENTATION_ERROR_COUNT.get() + " Instrumentation errors during test";
  }

  @AfterClass
  public static synchronized void agentCleanup() {
    if (null != activeTransformer) {
      instrumentation.removeTransformer(activeTransformer);
      activeTransformer = null;
    }
  }

  public static class ErrorCountingListener implements AgentBuilder.Listener {
    private static final List<AgentTestRunner> activeTests = new CopyOnWriteArrayList<>();

    public void activateTest(final AgentTestRunner testRunner) {
      activeTests.add(testRunner);
    }

    public void deactivateTest(final AgentTestRunner testRunner) {
      activeTests.remove(testRunner);
    }

    @Override
    public void onDiscovery(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded) {}

    @Override
    public void onTransformation(
        final TypeDescription typeDescription,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded,
        final DynamicType dynamicType) {
      TRANSFORMED_CLASSES.add(typeDescription.getActualName());
    }

    @Override
    public void onIgnored(
        final TypeDescription typeDescription,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded) {}

    @Override
    public void onError(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded,
        final Throwable throwable) {
      for (final AgentTestRunner testRunner : activeTests) {
        if (testRunner.onInstrumentationError(typeName, classLoader, module, loaded, throwable)) {
          INSTRUMENTATION_ERROR_COUNT.incrementAndGet();
          break;
        }
      }
    }

    @Override
    public void onComplete(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded) {}
  }
}
