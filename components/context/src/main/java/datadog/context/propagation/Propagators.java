package datadog.context.propagation;

import static java.util.Collections.synchronizedMap;
import static java.util.Comparator.comparingInt;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * This class is the entrypoint of the context propagation API allowing to retrieve the {@link
 * Propagator} to use.
 */
public final class Propagators {
  private static final Map<Concern, RegisteredPropagator> PROPAGATORS =
      synchronizedMap(new IdentityHashMap<>());
  private static final RegisteredPropagator NOOP =
      RegisteredPropagator.of(NoopPropagator.INSTANCE, false);
  private static volatile Propagator defaultPropagator = null;
  private static volatile boolean rebuildDefaultPropagator = true;

  private Propagators() {}

  /**
   * Gets the default propagator that applies all registered propagators in their priority order.
   *
   * @return The default propagator.
   */
  public static Propagator defaultPropagator() {
    if (rebuildDefaultPropagator) {
      Propagator[] propagatorsByPriority =
          PROPAGATORS.entrySet().stream()
              .filter(entry -> entry.getValue().isUsedAsDefault())
              .sorted(comparingInt(entry -> entry.getKey().priority()))
              .map(Map.Entry::getValue)
              .map(RegisteredPropagator::propagator)
              .toArray(Propagator[]::new);
      defaultPropagator = composite(propagatorsByPriority);
      rebuildDefaultPropagator = false;
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
    return PROPAGATORS.getOrDefault(concern, NOOP).propagator();
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
   * @return the composite propagator that will apply the propagators in their given order for
   *     context extraction, and reverse given order for context injection.
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
    register(concern, propagator, true);
  }

  /**
   * Registers a propagator for concern.
   *
   * @param concern The concern to register a propagator for.
   * @param propagator The propagator to register.
   * @param usedAsDefault Whether the propagator should be used as default propagator.
   * @see Propagators#defaultPropagator()
   */
  public static void register(Concern concern, Propagator propagator, boolean usedAsDefault) {
    PROPAGATORS.put(concern, RegisteredPropagator.of(propagator, usedAsDefault));
    if (usedAsDefault) {
      rebuildDefaultPropagator = true;
    }
  }

  /** Clear all registered propagators. For testing purpose only. */
  static void reset() {
    PROPAGATORS.clear();
    rebuildDefaultPropagator = true;
  }

  static class RegisteredPropagator {
    private final Propagator propagator;
    private final boolean usedAsDefault;

    private RegisteredPropagator(Propagator propagator, boolean usedAsDefault) {
      this.propagator = propagator;
      this.usedAsDefault = usedAsDefault;
    }

    static RegisteredPropagator of(Propagator propagator, boolean useAsDefault) {
      return new RegisteredPropagator(propagator, useAsDefault);
    }

    Propagator propagator() {
      return this.propagator;
    }

    boolean isUsedAsDefault() {
      return this.usedAsDefault;
    }
  }
}
