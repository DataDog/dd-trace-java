package com.datadog.openfeature.internal.ufc;

public final class Variant {
  public final String key;

  // A JSON flag's object or array value is converted to its shared OpenFeature Value when it is
  // first served, and the parsed tree is then dropped, so variants that are never served keep only
  // the parsed tree.
  private volatile Object value;
  private final boolean freezeOnFirstUse;

  public Variant(final String key, final Object value) {
    this(key, value, false);
  }

  Variant(final String key, final Object value, final boolean freezeOnFirstUse) {
    this.key = key;
    this.value = value;
    this.freezeOnFirstUse = freezeOnFirstUse;
  }

  /**
   * Returns the variant's value. For a JSON flag's object or array, that is a read-only {@link
   * dev.openfeature.sdk.Value} built on the first call and then shared by every evaluation.
   */
  public Object value() {
    final Object current = this.value;
    if (!this.freezeOnFirstUse || !FrozenValues.isContainer(current)) {
      return current;
    }
    // A race builds equal read-only values; any of them can be kept.
    final Object frozen = FrozenValues.freeze(current);
    this.value = frozen;
    return frozen;
  }
}
