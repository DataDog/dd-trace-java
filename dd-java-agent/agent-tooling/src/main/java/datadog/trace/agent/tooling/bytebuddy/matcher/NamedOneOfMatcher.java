package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.matcher.ElementMatcher;

public class NamedOneOfMatcher {

  /**
   * Matches a {@link NamedElement} for its exact name's membership of a set.
   *
   * @param names The expected names.
   * @param <T> The type of the matched object.
   * @return An element matcher checking if an element's exact name is a member of a set.
   */
  public static <T extends NamedElement> ElementMatcher.Junction<T> namedOneOf(String... names) {
    return new SetMatcher<>(names);
  }

  private static class SetMatcher<T extends NamedElement>
      extends ElementMatcher.Junction.AbstractBase<T> {

    // TODO better to use equality/prefix based set,
    // they take up less space and are membership checks quicker
    private final Set<String> values;

    private SetMatcher(String... values) {
      this.values = new HashSet<>(Arrays.asList(values));
    }

    @Override
    public boolean matches(T target) {
      return values.contains(target.getActualName());
    }
  }
}
