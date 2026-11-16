package com.datadog.openfeature.internal.ufc;

import dev.openfeature.sdk.Value;

public final class Variant {
  public final String key;
  public final Object value;

  /**
   * For a JSON flag whose value is an object or an array: the value as a shared {@link Value} that
   * cannot change; otherwise {@code null}. An evaluation that requests a {@link Value} returns it
   * instead of converting {@link #value} again.
   */
  public final Value frozenValue;

  public Variant(final String key, final Object value) {
    this(key, value, null);
  }

  public Variant(final String key, final Object value, final Value frozenValue) {
    this.key = key;
    this.value = value;
    this.frozenValue = frozenValue;
  }
}
