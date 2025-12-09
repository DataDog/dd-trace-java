package datadog.context.propagation;

import static java.util.Objects.requireNonNull;

import datadog.context.Context;

/** This class defines a cross-cutting concern to be propagated from a {@link Context}. */
public class Concern {
  /** The concern default priority. */
  public static final int DEFAULT_PRIORITY = 100;

  /** The concern name, for debugging purpose only. */
  private final String name;

  /** The concern priority, lower value means higher priority. */
  private final int priority;

  /**
   * Creates a concern.
   *
   * @param name the concern name, for debugging purpose only.
   * @return The created concern.
   */
  public static Concern named(String name) {
    return new Concern(name, DEFAULT_PRIORITY);
  }

  /**
   * Creates a concern with a specific priority.
   *
   * @param name the concern name, for debugging purpose only.
   * @param priority the concern priority (lower value means higher priority, while the default is
   *     {@link #DEFAULT_PRIORITY}),
   * @return The created concern.
   */
  public static Concern withPriority(String name, int priority) {
    return new Concern(name, priority);
  }

  private Concern(String name, int priority) {
    requireNonNull(name, "Concern name cannot be null");
    if (priority < 0) {
      throw new IllegalArgumentException("Concern priority cannot be negative");
    }
    this.name = name;
    this.priority = priority;
  }

  int priority() {
    return this.priority;
  }

  // We want identity equality, so no need to override equals().

  @Override
  public String toString() {
    return this.name;
  }
}
