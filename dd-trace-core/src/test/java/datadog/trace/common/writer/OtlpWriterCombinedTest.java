package datadog.trace.common.writer;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test for {@link OtlpWriter} over a real HTTP transport.
 *
 * <p>Intended to mirror the real-server cases in {@code DDAgentWriterCombinedTest} / {@code
 * DDIntakeWriterCombinedTest}: stand up a {@code TestHttpServer}, point an {@code OtlpWriter} at
 * it, write a trace, and assert on the wire-level request (path, Content-Type, Content-Encoding,
 * body).
 *
 * <p><b>Blocker discovered during a first attempt:</b> the precedent's {@code createMinimalTrace()}
 * helper builds a {@code DDSpanContext} with {@code PrioritySampling.UNSET}. {@link
 * OtlpPayloadDispatcher#shouldExport} only forwards spans with {@code samplingPriority() > 0} (or
 * with {@code SPAN_SAMPLING_MECHANISM_TAG} set), so the test span is filtered out and no HTTP
 * request is ever produced. Constructor-level {@code SAMPLER_KEEP} is rejected by {@code
 * SamplingMechanism.validateWithSamplingPriority(UNKNOWN, ...)}, and setting the mechanism tag via
 * {@code span.setTag(...)} after construction did not make the dispatcher pass the span through
 * either — implying a second drop point that wasn't isolated.
 *
 * <p>what is the best way to construct a properly-sampled real {@code DDSpan} in a unit test (i.e.
 * via a real {@code CoreTracer} + sampler)?
 */
class OtlpWriterCombinedTest {

  @Test
  @Disabled("TODO: see class Javadoc — blocked on properly-sampled test span fixture")
  void endToEndOverHttp() {}
}
