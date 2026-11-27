package com.datadog.openfeature.internal.evaluation;

import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.Rule;
import dev.openfeature.sdk.EvaluationContext;
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
 * attribute gains nothing from keeping them, so it is evaluated without a scope.
 */
final class EvaluationScope {
  private static final String ID = "id";
  private static final Object NULL = new Object();
  // A few attributes are compared in place; more go to a map, so that the lookups stay constant.
  private static final int MAX_SCANNED = 8;

  private final EvaluationContext context;
  // Most flags read one attribute: it is kept without allocating the arrays.
  private String firstName;
  private Object firstValue;
  private String[] names;
  private Object[] values;
  private int size;
  private Map<String, Object> overflow;

  /** A scope that keeps the attributes it resolves; see {@link #readsAnAttributeTwice}. */
  EvaluationScope(final EvaluationContext context) {
    this.context = context;
  }

  /** A scope for the flag when it reads an attribute twice, or {@code null} to read the context. */
  static EvaluationScope forFlag(final Flag flag, final EvaluationContext context) {
    return flag.preparedRules(EvaluationScope::readsAnAttributeTwice)
        ? new EvaluationScope(context)
        : null;
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
    if (name == null) {
      // A condition without an attribute is read as before, but never kept.
      return resolve(context, name);
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
    final Object value = resolve(context, name);
    remember(name, value == null ? NULL : value);
    return value;
  }

  /** Reads an attribute from the context, as a flag that keeps no attributes does. */
  static Object resolve(final EvaluationContext context, final String name) {
    // Special handling for "id" attribute: if not explicitly provided, use targeting key
    if (ID.equals(name) && !context.keySet().contains(name)) {
      return context.getTargetingKey();
    }
    return context.convertValue(context.getValue(name));
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
