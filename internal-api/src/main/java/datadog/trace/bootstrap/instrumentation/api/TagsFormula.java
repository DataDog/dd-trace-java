package datadog.trace.bootstrap.instrumentation.api;

/**
 * A formula for several derived tags that belong together, such as {@code peer.service} and the tag
 * naming its source. Its outputs are all or nothing: an explicit write to any of its targets
 * suppresses the whole formula. Outputs that don't belong together should be separate {@link
 * TagFormula}s.
 *
 * <p>Like {@link TagFormula}, it must be cheap and pure.
 */
@FunctionalInterface
public interface TagsFormula {
  /**
   * Computes the derived tags.
   *
   * @param in the span's tags and fields; every read is recorded as a dependency
   * @param out receives the outputs; only declared targets may be set
   */
  void compute(TagReader in, TagWriter out);
}
