package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.matcher.ElementMatcher;

public final class NameMatchers {

  /**
   * Matches a {@link NamedElement} for its exact name's membership of a set.
   *
   * @param names The expected names.
   * @param <T> The type of the matched object.
   * @return An element matcher checking if an element's exact name is a member of a set.
   */
  public static <T extends NamedElement> ElementMatcher.Junction<T> namedOneOf(String... names) {
    return new SetMatcher<>(true, names);
  }

  /**
   * Matches a {@link NamedElement} for its exact name's membership of a set.
   *
   * @param names The expected names.
   * @param <T> The type of the matched object.
   * @return An element matcher checking if an element's exact name is a member of a set.
   */
  public static <T extends NamedElement> ElementMatcher.Junction<T> namedOneOf(
      Collection<String> names) {
    return new SetMatcher<>(true, names);
  }

  /**
   * Matches a {@link NamedElement} for its exact name's absence from a set.
   *
   * @param names The expected names.
   * @param <T> The type of the matched object.
   * @return An element matcher checking if an element's exact name is absent from a set.
   */
  public static <T extends NamedElement> ElementMatcher.Junction<T> namedNoneOf(String... names) {
    return new SetMatcher<>(false, names);
  }

  /**
   * Matches a {@link NamedElement} for its name's prefix.
   *
   * @param prefix The expected name's prefix.
   * @param <T> The type of the matched object.
   * @return An element matcher for a named element's name's prefix.
   */
  public static <T extends NamedElement> ElementMatcher.Junction<T> nameStartsWith(String prefix) {
    return PrefixMatcher.of(prefix);
  }

  /**
   * Matches a {@link NamedElement} for its exact name.
   *
   * @param name The expected name.
   * @param <T> The type of the matched object.
   * @return An element matcher for a named element's exact name.
   */
  public static <T extends NamedElement> ElementMatcher.Junction<T> named(String name) {
    return NameMatcher.of(name);
  }

  private static class SetMatcher<T extends NamedElement>
      extends ElementMatcher.Junction.AbstractBase<T> {

    private final boolean include;
    private final Set<String> values;

    private SetMatcher(boolean include, String... values) {
      this.include = include;
      this.values = new HashSet<>(Arrays.asList(values));
    }

    private SetMatcher(boolean include, Collection<String> values) {
      this.include = include;
      this.values = new HashSet<>(values.size() * 2);
      this.values.addAll(values);
    }

    @Override
    public boolean matches(T target) {
      boolean contained = values.contains(target.getActualName());
      return (include && contained) || (!include && !contained);
    }
  }

  private static final class NameMatcher<T extends NamedElement>
      extends ElementMatcher.Junction.AbstractBase<T> {
    private static final ConcurrentHashMap<String, NameMatcher<? extends NamedElement>>
        DEDUPLICATOR = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    static <T extends NamedElement> NameMatcher<T> of(String name) {
      // TODO use computeIfAbsent when baselining on JDK8
      NameMatcher<?> matcher = DEDUPLICATOR.get(name);
      if (null == matcher) {
        matcher = new NameMatcher<>(name);
        NameMatcher<?> predecessor = DEDUPLICATOR.putIfAbsent(name, matcher);
        if (null != predecessor) {
          matcher = predecessor;
        }
      }
      return (NameMatcher<T>) matcher;
    }

    private final String name;

    private NameMatcher(String name) {
      this.name = name;
    }

    @Override
    public boolean matches(T target) {
      return target.getActualName().equals(name);
    }
  }

  private static final class PrefixMatcher<T extends NamedElement>
      extends ElementMatcher.Junction.AbstractBase<T> {

    private static final ConcurrentHashMap<String, PrefixMatcher<? extends NamedElement>>
        DEDUPLICATOR = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    static <T extends NamedElement> PrefixMatcher<T> of(String prefix) {
      // TODO use computeIfAbsent when baselining on JDK8
      PrefixMatcher<?> matcher = DEDUPLICATOR.get(prefix);
      if (null == matcher) {
        matcher = new PrefixMatcher<>(prefix);
        PrefixMatcher<?> predecessor = DEDUPLICATOR.putIfAbsent(prefix, matcher);
        if (null != predecessor) {
          matcher = predecessor;
        }
      }
      return (PrefixMatcher<T>) matcher;
    }

    private final String prefix;

    private PrefixMatcher(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public boolean matches(T target) {
      return target.getActualName().startsWith(prefix);
    }
  }
}
