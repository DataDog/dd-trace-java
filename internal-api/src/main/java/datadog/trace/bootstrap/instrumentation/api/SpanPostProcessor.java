package datadog.trace.bootstrap.instrumentation.api;

import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;

/**
 * Applies post-processing of spans before serialization.
 *
 * <p>Post-processing runs in TraceProcessingWorker thread. This provides the following properties:
 * <li>Runs in a single thread. Post-processing for each span runs sequentially.
 * <li>Runs after the request end, and does not block the main thread.
 * <li>Runs at a point where the sampler decision is already available.
 */
public interface SpanPostProcessor {

  /**
   * Post-processes a span, if needed.
   *
   * <p>Implementations should use {@code timeoutCheck}, and if true, they should halt processing as
   * much as possible. This method is guaranteed to be called even if post-processing of previous
   * spans have timed out.
   */
  void process(@Nonnull AgentSpan span, @Nonnull BooleanSupplier timeoutCheck);

  class Holder {
    public static final SpanPostProcessor NOOP = new NoOpSpanPostProcessor();

    // XXX: At the moment, a single post-processor can be registered, and only AppSec defines one.
    // If other products add their own, we'll need to refactor this to support multiple processors.
    public static volatile SpanPostProcessor INSTANCE = NOOP;
  }

  class NoOpSpanPostProcessor implements SpanPostProcessor {
    @Override
    public void process(@Nonnull AgentSpan span, @Nonnull BooleanSupplier timeoutCheck) {}
  }
}
