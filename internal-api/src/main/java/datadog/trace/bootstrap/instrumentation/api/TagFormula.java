package datadog.trace.bootstrap.instrumentation.api;

import javax.annotation.Nullable;

/**
 * A formula for one derived tag: a function of other tags. It must be cheap and pure: read only
 * through {@code in}, keep no state, and capture nothing that changes. Anything expensive belongs
 * in a deferred value, not a formula.
 */
@FunctionalInterface
public interface TagFormula {
  /**
   * Computes the derived tag.
   *
   * @param in the span's tags and fields; every read is recorded as a dependency
   * @return the value, or null to leave the tag absent
   */
  @Nullable
  Object compute(TagReader in);
}
