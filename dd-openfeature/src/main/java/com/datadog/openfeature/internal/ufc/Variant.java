package com.datadog.openfeature.internal.ufc;

import dev.openfeature.sdk.Value;

public final class Variant {
  public final String key;
  public final Object value;

  /**
   * The value as a shared {@link Value} that cannot change, for a JSON object or array value; or
   * {@code null}. Evaluations return it instead of converting {@link #value} again.
   */
  public final Value frozenValue;

  public Variant(final String key, final Object value) {
    this.key = key;
    this.value = value;
    this.frozenValue = FrozenValues.isContainer(value) ? FrozenValues.freeze(value) : null;
  }
}
