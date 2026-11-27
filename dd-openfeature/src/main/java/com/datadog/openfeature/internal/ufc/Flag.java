package com.datadog.openfeature.internal.ufc;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class Flag {
  public final String key;
  public final boolean enabled;
  public final ValueType variationType;
  public final Map<String, Variant> variations;
  public final List<Allocation> allocations;

  // What the evaluator derives from the allocations' rules, built when the flag is first evaluated.
  private volatile Object preparedRules;

  public Flag(
      final String key,
      final boolean enabled,
      final ValueType variationType,
      final Map<String, Variant> variations,
      final List<Allocation> allocations) {
    this.key = key;
    this.enabled = enabled;
    this.variationType = variationType;
    this.variations = variations;
    this.allocations = allocations;
  }

  /**
   * Returns what {@code prepare} derives from the allocations, computed once for the flag. Two
   * evaluations that reach the flag first at the same time can both compute it; either result is
   * kept. Every caller passes the same function, so the cached result does not depend on which one
   * ran first.
   *
   * @param prepare derives the prepared form from {@link #allocations}, which can be {@code null}.
   *     It never returns {@code null}.
   * @return the prepared form.
   */
  @SuppressWarnings("unchecked")
  public <T> T preparedRules(final Function<List<Allocation>, T> prepare) {
    Object prepared = preparedRules;
    if (prepared == null) {
      prepared = prepare.apply(allocations);
      preparedRules = prepared;
    }
    return (T) prepared;
  }
}
