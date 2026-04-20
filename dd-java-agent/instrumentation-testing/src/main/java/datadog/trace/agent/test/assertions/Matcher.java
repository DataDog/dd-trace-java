package datadog.trace.agent.test.assertions;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * This interface represents a generic matcher to evaluate whether a given value matches certain
 * criteria defined by the implementation.
 *
 * @param <T> the type of the value being matched.
 */
public interface Matcher<T> extends Predicate<T> {
  /**
   * Gets the expected value for this matcher, if any.
   *
   * @return The expected value wrapped into an {@link Optional}, or {@link Optional#empty()} if no
   *     specific value is expected.
   */
  Optional<T> expected();

  /**
   * Explains the reason why the value does not match the expectation.
   *
   * @return The failure reason.
   */
  String failureReason();
}
