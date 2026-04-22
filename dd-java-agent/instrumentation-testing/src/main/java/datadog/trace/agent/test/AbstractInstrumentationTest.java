package datadog.trace.agent.test;

import static java.util.function.Function.identity;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.instrument.classinject.ClassInjector;
import datadog.trace.agent.test.assertions.TraceAssertions;
import datadog.trace.agent.test.assertions.TraceMatcher;
import datadog.trace.agent.tooling.AgentInstaller;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers;
import datadog.trace.api.Config;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.PendingTrace;
import datadog.trace.core.TraceCollector;
import datadog.trace.junit.utils.context.AllowContextTestingExtension;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opentest4j.AssertionFailedError;

/**
 * Base class for instrumentation tests using JUnit Jupiter.
 *
 * <p>It is still early development, and the overall API might change to leverage its extension
 * model. The current implementation is inspired and kept close to its Groovy / Spock counterpart,
 * the {@code InstrumentationSpecification}.
 *
 * <ul>
 *   <li>{@code @BeforeAll}: Installs the agent and creates a shared tracer
 *   <li>{@code @BeforeEach}: Flushes and resets the writer
 *   <li>{@code @AfterEach}: Flushes the tracer
 *   <li>{@code @AfterAll}: Closes the tracer and removes the agent transformer
 * </ul>
 */
@ExtendWith({TestClassShadowingExtension.class, AllowContextTestingExtension.class})
public abstract class AbstractInstrumentationTest {
  static final Instrumentation INSTRUMENTATION = ByteBuddyAgent.getInstrumentation();

  static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(20);

  protected static final InstrumentationTestConfig testConfig = new InstrumentationTestConfig();

  protected static TracerAPI tracer;
  protected static ListWriter writer;
  private static ClassFileTransformer activeTransformer;
  private static ClassFileTransformerListener transformerListener;

  @BeforeAll
  static void initAll() {
    // If this fails, it's likely the result of another test loading Config before it can be
    // injected into the bootstrap classpath.
    assertNull(Config.class.getClassLoader(), "Config must load on the bootstrap classpath.");

    // Create shared test writer and tracer
    writer = new ListWriter();
    CoreTracer coreTracer =
        CoreTracer.builder()
            .writer(writer)
            .idGenerationStrategy(IdGenerationStrategy.fromName(testConfig.idGenerationStrategy))
            .strictTraceWrites(testConfig.strictTraceWrites)
            .build();
    TracerInstaller.forceInstallGlobalTracer(coreTracer);
    tracer = coreTracer;

    ClassInjector.enableClassInjection(INSTRUMENTATION);

    // if a test enables the instrumentation it verifies,
    // the cache needs to be recomputed taking into account that instrumentation's matchers
    ClassLoaderMatchers.resetState();

    assertTrue(
        ServiceLoader.load(
                InstrumenterModule.class, AbstractInstrumentationTest.class.getClassLoader())
            .iterator()
            .hasNext(),
        "No instrumentation found");
    transformerListener = new ClassFileTransformerListener();
    activeTransformer =
        AgentInstaller.installBytebuddyAgent(
            INSTRUMENTATION, true, AgentInstaller.getEnabledSystems(), transformerListener);
  }

  @BeforeEach
  public void init() {
    tracer.flush();
    writer.start();
  }

  @AfterEach
  public void tearDown() {
    tracer.flush();
  }

  @AfterAll
  static void tearDownAll() {
    if (tracer != null) {
      tracer.close();
      tracer = null;
    }
    if (writer != null) {
      writer.close();
      writer = null;
    }
    if (activeTransformer != null) {
      INSTRUMENTATION.removeTransformer(activeTransformer);
      activeTransformer = null;
    }
    // All cleanups should happen before this verify call.
    // If not, a failing assertion may prevent cleanup
    if (transformerListener != null) {
      transformerListener.verify();
      transformerListener = null;
    }
  }

  /**
   * Checks the structure of the traces captured from the test tracer.
   *
   * @param matchers The matchers to verify the trace collection, one matcher by expected trace.
   */
  protected void assertTraces(TraceMatcher... matchers) {
    assertTraces(identity(), matchers);
  }

  /**
   * Checks the structure of the traces captured from the test tracer.
   *
   * @param options The {@link TraceAssertions.Options} to configure the checks.
   * @param matchers The matchers to verify the trace collection, one matcher by expected trace.
   */
  protected void assertTraces(
      Function<TraceAssertions.Options, TraceAssertions.Options> options,
      TraceMatcher... matchers) {
    int expectedTraceCount = matchers.length;
    try {
      writer.waitForTraces(expectedTraceCount);
    } catch (InterruptedException | TimeoutException e) {
      throw new AssertionFailedError("Timeout while waiting for traces", e);
    }
    TraceAssertions.assertTraces(writer, options, matchers);
  }

  /**
   * Blocks the current thread until the traces written match the given predicate or the timeout
   * occurs.
   *
   * @param predicate the condition that must be satisfied by the list of traces
   */
  protected void blockUntilTracesMatch(Predicate<List<List<DDSpan>>> predicate) {
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (!predicate.test(writer)) {
      if (System.currentTimeMillis() > deadline) {
        throw new RuntimeException(new TimeoutException("Timed out waiting for traces/spans."));
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  protected void blockUntilChildSpansFinished(int numberOfSpans) {
    blockUntilChildSpansFinished(tracer.activeSpan(), numberOfSpans);
  }

  static void blockUntilChildSpansFinished(AgentSpan span, int numberOfSpans) {
    if (span instanceof DDSpan) {
      TraceCollector traceCollector = ((DDSpan) span).context().getTraceCollector();
      if (!(traceCollector instanceof PendingTrace)) {
        throw new IllegalStateException(
            "Expected PendingTrace trace collector, got " + traceCollector.getClass().getName());
      }

      PendingTrace pendingTrace = (PendingTrace) traceCollector;
      long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;

      while (pendingTrace.size() < numberOfSpans) {
        if (System.currentTimeMillis() > deadline) {
          throw new RuntimeException(
              new TimeoutException(
                  "Timed out waiting for child spans. Received: " + pendingTrace.size()));
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  /** Configuration for {@link AbstractInstrumentationTest}. */
  protected static class InstrumentationTestConfig {
    private String idGenerationStrategy = "SEQUENTIAL";
    private boolean strictTraceWrites = true;

    public InstrumentationTestConfig idGenerationStrategy(String strategy) {
      this.idGenerationStrategy = strategy;
      return this;
    }

    public InstrumentationTestConfig strictTraceWrites(boolean strict) {
      this.strictTraceWrites = strict;
      return this;
    }
  }
}
