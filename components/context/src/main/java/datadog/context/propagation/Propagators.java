package datadog.context.propagation;

import static java.util.Collections.synchronizedMap;
import static java.util.Comparator.comparingInt;

import java.util.IdentityHashMap;
import java.util.Map;

public final class Propagators {
  private static final Map<Concern, Propagator> PROPAGATORS =
      synchronizedMap(new IdentityHashMap<>());
  private static volatile Propagator defaultPropagator = null;
  private static volatile boolean defaultPropagatorSet = false;

  private Propagators() {}

  /**
   * Gets the default propagator that applies all registered propagators in their priority order.
   *
   * @return The default propagator.
   */
  public static Propagator defaultPropagator() {
    if (!defaultPropagatorSet) {
      Propagator[] propagatorsByPriority =
          PROPAGATORS.entrySet().stream()
              .sorted(comparingInt(entry -> entry.getKey().priority()))
              .map(Map.Entry::getValue)
              .toArray(Propagator[]::new);
      defaultPropagator = composite(propagatorsByPriority);
      defaultPropagatorSet = true;
    }
    return defaultPropagator;
  }

  /**
   * Gets the propagator for a given concern.
   *
   * @param concern the concern to get propagator for.
   * @return the related propagator if registered, a {@link #noop()} propagator otherwise.
   */
  public static Propagator forConcern(Concern concern) {
    return PROPAGATORS.getOrDefault(concern, NoopPropagator.INSTANCE);
  }

  /**
   * Gets the propagator for the given concerns.
   *
   * @param concerns the concerns to get propagators for.
   * @return A propagator that will apply the concern propagators if registered, in the given
   *     concern order.
   */
  public static Propagator forConcerns(Concern... concerns) {
    Propagator[] propagators = new Propagator[concerns.length];
    for (int i = 0; i < concerns.length; i++) {
      propagators[i] = forConcern(concerns[i]);
    }
    return composite(propagators);
  }

  /**
   * Returns a noop propagator.
   *
   * @return a noop propagator.
   */
  public static Propagator noop() {
    return NoopPropagator.INSTANCE;
  }

  /**
   * Creates a composite propagator.
   *
   * @param propagators the elements that composes the returned propagator.
   * @return the composite propagator that will apply the propagators in their given order.
   */
  public static Propagator composite(Propagator... propagators) {
    if (propagators.length == 0) {
      return NoopPropagator.INSTANCE;
    } else if (propagators.length == 1) {
      return propagators[0];
    } else {
      return new CompositePropagator(propagators);
    }
  }

  /**
   * Registers a propagator for concern.
   *
   * @param concern The concern to register a propagator for.
   * @param propagator The propagator to register.
   */
  public static void register(Concern concern, Propagator propagator) {
    PROPAGATORS.put(concern, propagator);
    defaultPropagatorSet = false;
  }

  /** Clear all registered propagators. For testing purpose only. */
  static void reset() {
    PROPAGATORS.clear();
    defaultPropagatorSet = false;
  }
}
