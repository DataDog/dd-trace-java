package com.datadog.openfeature.internal.evaluation;

import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.Rule;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Value;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state of one flag evaluation. It resolves each context attribute once, however many
 * conditions read it: a flag with many allocations reads the same attributes in each of them, and
 * resolving one means a context lookup and a value conversion that can allocate.
 *
 * <p>An attribute is read from the context the first time a condition needs it. A context that
 * changes during the evaluation is not read again. A flag whose conditions each read a different
 * attribute gains nothing from keeping them, so its attributes are read directly.
 */
final class EvaluationScope {
  private static final String ID = "id";
  private static final Object NULL = new Object();
  // A few attributes are compared in place; more go to a map, so that the lookups stay constant.
  private static final int MAX_SCANNED = 8;

  private final EvaluationContext context;
  private final boolean cached;
  // Most flags read one attribute: it is kept without allocating the arrays.
  private String firstName;
  private Object firstValue;
  private String[] names;
  private Object[] values;
  private int size;
  private Map<String, Object> overflow;

  /** A scope that keeps the attributes it resolves. */
  EvaluationScope(final EvaluationContext context) {
    this(context, true);
  }

  /**
   * @param cached whether the resolved attributes are kept; see {@link #readsAnAttributeTwice}.
   */
  EvaluationScope(final EvaluationContext context, final boolean cached) {
    this.context = context;
    this.cached = cached;
  }

  /**
   * Whether two conditions of the allocations read the same attribute, which is when keeping the
   * resolved attributes saves work.
   */
  static Boolean readsAnAttributeTwice(final List<Allocation> allocations) {
    if (allocations == null) {
      return Boolean.FALSE;
    }
    final Set<String> attributes = new HashSet<>();
    for (final Allocation allocation : allocations) {
      if (allocation == null || allocation.rules == null) {
        continue;
      }
      for (final Rule rule : allocation.rules) {
        if (rule == null || rule.conditions == null) {
          continue;
        }
        for (final ConditionConfiguration condition : rule.conditions) {
          if (condition != null
              && condition.attribute != null
              && !attributes.add(condition.attribute)) {
            return Boolean.TRUE;
          }
        }
      }
    }
    return Boolean.FALSE;
  }

  /** Returns the attribute's value, or {@code null} when the context does not hold it. */
  Object attribute(final String name) {
    if (!cached || name == null) {
      // A condition without an attribute is read as before, but never kept.
      return resolve(name);
    }
    // String.equals compares the references first, so the same name returns at once.
    if (name.equals(firstName)) {
      return firstValue == NULL ? null : firstValue;
    }
    for (int i = 0; i < size; i++) {
      if (name.equals(names[i])) {
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
