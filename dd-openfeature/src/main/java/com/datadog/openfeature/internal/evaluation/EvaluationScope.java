package com.datadog.openfeature.internal.evaluation;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Value;
import java.util.HashMap;
import java.util.Map;

/**
 * The state of one flag evaluation. It resolves each context attribute once, however many
 * conditions read it: a flag with many allocations reads the same attributes in each of them, and
 * resolving one means a context lookup and a value conversion that can allocate.
 *
 * <p>An attribute is read from the context the first time a condition needs it. A context that
 * changes during the evaluation is not read again.
 */
final class EvaluationScope {
  private static final String ID = "id";
  private static final Object NULL = new Object();
  // A few attributes are compared in place; more go to a map, so that the lookups stay constant.
  private static final int MAX_SCANNED = 8;

  final EvaluationContext context;
  // Most flags read one attribute: it is kept without allocating the arrays.
  private String firstName;
  private Object firstValue;
  private String[] names;
  private Object[] values;
  private int size;
  private Map<String, Object> overflow;

  EvaluationScope(final EvaluationContext context) {
    this.context = context;
  }

  /** Returns the attribute's value, or {@code null} when the context does not hold it. */
  Object attribute(final String name) {
    final String first = firstName;
    if (first == name || (first != null && first.equals(name))) {
      return firstValue == NULL ? null : firstValue;
    }
    for (int i = 0; i < size; i++) {
      final String cached = names[i];
      if (cached == name || cached.equals(name)) {
        final Object value = values[i];
        return value == NULL ? null : value;
      }
    }
    if (overflow != null) {
      final Object value = overflow.get(name);
      if (value != null) {
        return value == NULL ? null : value;
      }
    }
    final Object value = resolve(name);
    remember(name, value == null ? NULL : value);
    return value;
  }

  private Object resolve(final String name) {
    // getValue is null only for an absent key: an "id" that is not provided is the targeting key.
    // A context's keySet() is not used for this, as a layered context copies its keys to build it.
    final Value value = context.getValue(name);
    if (value == null) {
      return ID.equals(name) ? context.getTargetingKey() : null;
    }
    return context.convertValue(value);
  }

  private void remember(final String name, final Object value) {
    if (firstName == null) {
      firstName = name;
      firstValue = value;
      return;
    }
    if (names == null) {
      names = new String[MAX_SCANNED];
      values = new Object[MAX_SCANNED];
    } else if (size == MAX_SCANNED) {
      if (overflow == null) {
        overflow = new HashMap<>();
      }
      overflow.put(name, value);
      return;
    }
    names[size] = name;
    values[size] = value;
    size++;
  }
}
