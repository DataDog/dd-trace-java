package com.datadog.openfeature.internal.ufc;

import dev.openfeature.sdk.Value;

public final class Variant {
  public final String key;
  public final Object value;

  /** The shared, read-only value of a JSON object or array variant; otherwise {@code null}. */
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
