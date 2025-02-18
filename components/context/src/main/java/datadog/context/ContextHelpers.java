package datadog.context;

import static java.lang.Math.max;
import static java.util.Arrays.copyOfRange;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;

/**
 * Static helpers to manipulate context collections.
 *
 * <p>Typical usages include:
 *
 * <pre>{@code
 * // Finding a context value from multiple sources:
 * Span span = findFirst(spanKey, message, request, CURRENT)
 * // Find all context values from different sources:
 * List<Error> errors = findAll(errorKey, message, request, CURRENT)
 * // Capture multiple contexts in a single one:
 * Context aggregate = combine(message, request, CURRENT)
 * // Combine multiple contexts into a single one using custom merge rules:
 * Context combined = combine(
 *   (current, next) -> {
 *     var metric = current.get(metricKey);
 *     var nextMetric = next.get(metricKey);
 *     return current.with(metricKey, metric.add(nextMetric));
 *   }, message, request, CURRENT);
 * }</pre>
 *
 * where {@link #CURRENT} denotes a carrier with the current context.
 */
public final class ContextHelpers {
  /** A helper object carrying the {@link Context#current()} context. */
  public static final Object CURRENT = new Object();

  private ContextHelpers() {}

  /**
   * Find the first context value from given context carriers.
   *
   * @param key The key used to store the value.
   * @param carriers The carrier to get context and value from.
   * @param <T> The type of the value to look for.
   * @return The first context value found, {@code null} if not found.
   */
  public static <T> T findFirst(ContextKey<T> key, Object... carriers) {
    requireNonNull(key, "key cannot be null");
    for (Object carrier : carriers) {
      requireNonNull(carrier, "carrier cannot be null");
      Context context = carrier == CURRENT ? Context.current() : Context.from(carrier);
      T value = context.get(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  /**
   * Find all the context values from the given context carriers.
   *
   * @param key The key used to store the value.
   * @param carriers The carriers to get context and value from.
   * @param <T> The type of the values to look for.
   * @return A list of all values found, in context order.
   */
  public static <T> List<T> findAll(ContextKey<T> key, Object... carriers) {
    requireNonNull(key, "key cannot be null");
    List<T> values = new ArrayList<>(carriers.length);
    for (Object carrier : carriers) {
      requireNonNull(carrier, "carrier cannot be null");
      Context context = carrier == CURRENT ? Context.current() : Context.from(carrier);
      T value = context.get(key);
      if (value != null) {
        values.add(value);
      }
    }
    return values;
  }

  /**
   * Combine contexts and their values, keeping the first founds.
   *
   * @param contexts The contexts to combine.
   * @return A context containing all the values from all the given context, keeping the first value
   *     found for a given key.
   */
  public static Context combine(Context... contexts) {
    return combine(ContextHelpers::combineKeepingFirst, contexts);
  }

  /**
   * Combine multiple contexts into a single one.
   *
   * @param combiner The context combiner, taking already combined context as first parameter, any
   *     following one as second parameter, and returning the combined context.
   * @param contexts The contexts to combine.
   * @return The combined context.
   */
  public static Context combine(BinaryOperator<Context> combiner, Context... contexts) {
    requireNonNull(combiner, "combiner cannot be null");
    Context result = new IndexedContext(new Object[0]);
    for (Context context : contexts) {
      requireNonNull(context, "context cannot be null");
      result = combiner.apply(result, context);
    }
    return result;
  }

  private static Context combineKeepingFirst(Context current, Context next) {
    if (!(current instanceof IndexedContext)) {
      throw new IllegalStateException("Left context is supposed to be an IndexedContext");
    }
    IndexedContext currentIndexed = (IndexedContext) current;
    if (next instanceof EmptyContext) {
      return current;
    } else if (next instanceof SingletonContext) {
      SingletonContext nextSingleton = (SingletonContext) next;
      // Check if the single next value is already define in current so next context can be skipped
      if (nextSingleton.index < currentIndexed.store.length
          && currentIndexed.store[nextSingleton.index] != null) {
        return current;
      }
      // Always store next value otherwise
      Object[] store =
          copyOfRange(
              currentIndexed.store, 0, max(currentIndexed.store.length, nextSingleton.index + 1));
      store[nextSingleton.index] = nextSingleton.value;
      return new IndexedContext(store);
    } else if (next instanceof IndexedContext) {
      IndexedContext nextIndexed = (IndexedContext) next;
      // Don't prematurely allocate store. Only allocate if:
      // * nextIndexed has more values that currentIndexed,
      //   so the additional values will always be kept
      // * nextIndexed has values that currentIndexed do not have
      Object[] store = null;
      // Allocate store if nextIndexed has more elements than currentIndexed
      if (nextIndexed.store.length > currentIndexed.store.length) {
        store = copyOfRange(currentIndexed.store, 0, nextIndexed.store.length);
      }
      // Apply nextIndexed values if not set in currentIndexed
      for (int i = 0; i < currentIndexed.store.length; i++) {
        Object nextValue = nextIndexed.store[i];
        if (nextValue != null && currentIndexed.store[i] == null) {
          if (store == null) {
            store = copyOfRange(currentIndexed.store, 0, currentIndexed.store.length);
          }
          store[i] = nextValue;
        }
      }
      // Apply any additional values from nextIndexed if any
      for (int i = currentIndexed.store.length; i < nextIndexed.store.length; i++) {
        Object nextValue = nextIndexed.store[i];
        if (nextValue != null) {
          store[i] = nextValue;
        }
      }
      // If store was not allocated, no value from nextIndexed was taken
      return store == null ? current : new IndexedContext(store);
    }
    throw new IllegalStateException("Unsupported context type: " + next.getClass().getName());
  }
}
