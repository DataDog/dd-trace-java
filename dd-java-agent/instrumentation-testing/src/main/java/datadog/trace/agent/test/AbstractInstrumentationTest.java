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
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.PendingTrace;
import datadog.trace.core.TraceCollector;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opentest4j.AssertionFailedError;

/**
 * This class is an experimental base to run instrumentation tests using JUnit Jupiter. It is still
 * early development, and the overall API is expected to change to leverage its extension model. The
 * current implementation is inspired and kept close to it Groovy / Spock counterpart, the {@code
 * InstrumentationSpecification}.
 */
@ExtendWith(TestClassShadowingExtension.class)
public abstract class AbstractInstrumentationTest {
  static final Instrumentation INSTRUMENTATION = ByteBuddyAgent.getInstrumentation();

  static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(20);

  protected AgentTracer.TracerAPI tracer;

  protected ListWriter writer;

  protected ClassFileTransformer activeTransformer;
  protected ClassFileTransformerListener transformerLister;

  @BeforeEach
  public void init() {
    // If this fails, it's likely the result of another test loading Config before it can be
    // injected into the bootstrap classpath.
    // If one test extends AgentTestRunner in a module, all tests must extend
    assertNull(Config.class.getClassLoader(), "Config must load on the bootstrap classpath.");

    // Initialize test tracer
    this.writer = new ListWriter();
    // Initialize test tracer
    CoreTracer tracer =
        CoreTracer.builder()
            .writer(this.writer)
            .idGenerationStrategy(IdGenerationStrategy.fromName(idGenerationStrategyName()))
            .strictTraceWrites(useStrictTraceWrites())
            .build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    this.tracer = tracer;

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
    this.transformerLister = new ClassFileTransformerListener();
    this.activeTransformer =
        AgentInstaller.installBytebuddyAgent(
            INSTRUMENTATION, true, AgentInstaller.getEnabledSystems(), this.transformerLister);
  }

  protected String idGenerationStrategyName() {
    return "SEQUENTIAL";
  }

  private boolean useStrictTraceWrites() {
    return true;
  }

  @AfterEach
  public void tearDown() {
    this.tracer.close();
    this.writer.close();
    if (this.activeTransformer != null) {
      INSTRUMENTATION.removeTransformer(this.activeTransformer);
      this.activeTransformer = null;
    }

    // All cleanups should happen before these assertions.
    // If not, a failing assertion may prevent cleanup
    this.transformerLister.verify();
    this.transformerLister = null;
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
      this.writer.waitForTraces(expectedTraceCount);
    } catch (InterruptedException | TimeoutException e) {
      throw new AssertionFailedError("Timeout while waiting for traces", e);
    }
    TraceAssertions.assertTraces(this.writer, options, matchers);
  }

  protected void blockUntilChildSpansFinished(final int numberOfSpans) {
    blockUntilChildSpansFinished(this.tracer.activeSpan(), numberOfSpans);
  }

  static void blockUntilChildSpansFinished(AgentSpan span, int numberOfSpans) {
    if (span instanceof DDSpan) {
      TraceCollector traceCollector = ((DDSpan) span).context().getTraceCollector();
      if (!(traceCollector instanceof PendingTrace)) {
        throw new IllegalStateException(
            "Expected $PendingTrace.name trace collector, got $traceCollector.class.name");
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
}
