package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.function.Function;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.matcher.ElementMatcher;

public final class NameMatchers {

  /**
   * Matches a {@link NamedElement} for its exact name.
   *
   * @param name The expected name.
   * @param <T> The type of the matched object.
   * @return An element matcher for a named element's exact name.
   */
  @SuppressWarnings("unchecked")
  public static <T extends NamedElement> Named<T> named(String name) {
    return namedCache.computeIfAbsent(name, newNamedMatcher);
  }

  /**
   * Matches a {@link NamedElement} for its exact name's membership of a set.
   *
   * @param names The expected names.
   * @param <T> The type of the matched object.
   * @return An element matcher checking if an element's exact name is a member of a set.
   */
  public static <T extends NamedElement> OneOf<T> namedOneOf(String... names) {
    return namedOneOf(Arrays.asList(names));
  }

  /**
   * Matches a {@link NamedElement} for its exact name's membership of a set.
   *
   * @param names The expected names.
   * @param <T> The type of the matched object.
   * @return An element matcher checking if an element's exact name is a member of a set.
   */
  public static <T extends NamedElement> OneOf<T> namedOneOf(Collection<String> names) {
    return new OneOf<>(tryMakeImmutableSet(names));
  }

  /**
   * Matches a {@link NamedElement} for its exact name's absence from a set.
   *
   * @param names The expected names.
   * @param <T> The type of the matched object.
   * @return An element matcher checking if an element's exact name is absent from a set.
   */
  public static <T extends NamedElement> NoneOf<T> namedNoneOf(String... names) {
    return new NoneOf<>(tryMakeImmutableSet(Arrays.asList(names)));
  }

  /**
   * Matches a {@link NamedElement} for its name's prefix.
   *
   * @param prefix The expected name's prefix.
   * @param <T> The type of the matched object.
   * @return An element matcher for a named element's name's prefix.
   */
  public static <T extends NamedElement> StartsWith<T> nameStartsWith(String prefix) {
    return new StartsWith<>(prefix);
  }

  /**
   * Matches a {@link NamedElement} for its name's suffix.
   *
   * @param suffix The expected name's suffix.
   * @param <T> The type of the matched object.
   * @return An element matcher for a named element's name's suffix.
   */
  public static <T extends NamedElement> EndsWith<T> nameEndsWith(String suffix) {
    return new EndsWith<>(suffix);
  }

  /**
   * Matches a {@link NamedElement} for its name not being in an exclusion set.
   *
   * @param type The type of exclusion to apply
   * @param <T> The type of the matched object.
   * @return An element matcher checking if an element's exact name is a member of a set.
   */
  public static <T extends NamedElement> NotExcluded<T> notExcludedByName(
      ExcludeFilter.ExcludeType type) {
    return new NotExcluded<T>(type);
  }

  @SuppressWarnings("rawtypes")
  private static final DDCache<String, Named> namedCache = DDCaches.newFixedSizeCache(256);

  @SuppressWarnings("rawtypes")
  private static final Function<String, Named> newNamedMatcher =
      new Function<String, Named>() {
        @Override
        public Named apply(String input) {
          return new Named(input);
        }
      };

  public static final class Named<T extends NamedElement>
      extends ElementMatcher.Junction.ForNonNullValues<T> {
    final String name;

    Named(String name) {
      this.name = name;
    }

    @Override
    protected boolean doMatch(NamedElement target) {
      return name.equals(target.getActualName());
    }
  }

  public static final class StartsWith<T extends NamedElement>
      extends ElementMatcher.Junction.ForNonNullValues<T> {
    private final String name;

    StartsWith(String name) {
      this.name = name;
    }

    @Override
    protected boolean doMatch(NamedElement target) {
      return target.getActualName().startsWith(name);
    }
  }

  public static final class EndsWith<T extends NamedElement>
      extends ElementMatcher.Junction.ForNonNullValues<T> {
    private final String name;

    EndsWith(String name) {
      this.name = name;
    }

    @Override
    protected boolean doMatch(NamedElement target) {
      return target.getActualName().endsWith(name);
    }
  }

  public static final class OneOf<T extends NamedElement>
      extends ElementMatcher.Junction.ForNonNullValues<T> {
    final Set<String> names;

    OneOf(Set<String> names) {
      this.names = names;
    }

    @Override
    protected boolean doMatch(NamedElement target) {
      return names.contains(target.getActualName());
    }
  }

  public static final class NoneOf<T extends NamedElement>
      extends ElementMatcher.Junction.ForNonNullValues<T> {
    private final Set<String> names;

    NoneOf(Set<String> names) {
      this.names = names;
    }

    @Override
    protected boolean doMatch(NamedElement target) {
      return !names.contains(target.getActualName());
    }
  }

  public static final class NotExcluded<T extends NamedElement>
      extends ElementMatcher.Junction.ForNonNullValues<T> {
    private final ExcludeFilter.ExcludeType excludeType;

    NotExcluded(ExcludeFilter.ExcludeType excludeType) {
      this.excludeType = excludeType;
    }

    @Override
    protected boolean doMatch(NamedElement target) {
      return !ExcludeFilter.exclude(excludeType, target.getActualName());
    }
  }
}
