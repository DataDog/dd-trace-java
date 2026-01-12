package datadog.trace.agent.test.assertions;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * This interface represents a generic matcher to evaluate whether a given value matches certain
 * criteria defined by the implementation.
 *
 * @param <T> The type of the value being matched.
 */
public interface Matcher<T> extends Predicate<T> {
  Optional<T> expected();

  String message();
}
