package datadog.trace.core.postprocessor;

import datadog.trace.core.DDSpan;
import java.util.function.BooleanSupplier;

/**
 * Span Post-processing with a timeout check capability.
 *
 * <p>Implementations of this interface should carry out post-processing of spans while supporting
 * interruption when a specified time limit is exceeded. The method {@code process} must check the
 * state of {@code timeoutCheck} while processing span. If {@code timeoutCheck.getAsBoolean()}
 * returns {@code true}, processing should be immediately halted, and the method should return
 * {@code false}. If post-processing completes successfully before the timeout, the method should
 * return {@code true}.
 */
public interface SpanPostProcessor {
  /**
   * Post-processes a span.
   *
   * @param span The span to be post-processed.
   * @param timeoutCheck A timeout check returning {@code true} if the allotted time has elapsed.
   * @return {@code true} if the span was successfully processed; {@code false} in case of a
   *     timeout.
   */
  boolean process(DDSpan span, BooleanSupplier timeoutCheck);
}
