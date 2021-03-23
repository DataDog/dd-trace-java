package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.matcher.ElementMatcher;

public final class NameMatchers<T extends NamedElement>
    extends ElementMatcher.Junction.AbstractBase<T> {

  private static final int NAMED = 0;
  private static final int NAME_STARTS_WITH = 1;
  private static final int NAME_ENDS_WITH = 2;
  private static final int NAMED_ONE_OF = 3;
  private static final int NAMED_NONE_OF = 4;

  @SuppressWarnings("unchecked")
  private static final ConcurrentHashMap<String, NameMatchers<?>>[] DEDUPLICATORS =
      new ConcurrentHashMap[3];

  static {
    DEDUPLICATORS[0] = new ConcurrentHashMap<>();
    DEDUPLICATORS[1] = new ConcurrentHashMap<>();
    DEDUPLICATORS[2] = new ConcurrentHashMap<>();
  }

  @SuppressWarnings("unchecked")
  static <T extends NamedElement> NameMatchers<T> deduplicate(int mode, String data) {
    assert mode <= NAME_ENDS_WITH : "do not call with sets";
    // TODO use computeIfAbsent when baselining on JDK8
    ConcurrentHashMap<String, NameMatchers<?>> deduplicator = DEDUPLICATORS[mode];
    NameMatchers<T> matcher = (NameMatchers<T>) DEDUPLICATORS[mode].get(data);
    if (null == matcher) {
      matcher = new NameMatchers<>(mode, data);
      NameMatchers<T> predecessor = (NameMatchers<T>) deduplicator.putIfAbsent(data, matcher);
      if (null != predecessor) {
        matcher = predecessor;
      }
    }
    return matcher;
  }

  private final int mode;
  private final Object data;

  public NameMatchers(int mode, Object data) {
    assert data instanceof Set || data instanceof String;
    this.mode = mode;
    this.data = data;
  }

  /**
   * Matches a {@link NamedElement} for its exact name's membership of a set.
   *
   * @param names The expected names.
   * @param <T> The type of the matched object.
   * @return An element matcher checking if an element's exact name is a member of a set.
   */
  public static <T extends NamedElement> ElementMatcher.Junction<T> namedOneOf(String... names) {
    return new NameMatchers<>(NAMED_ONE_OF, toSet(names));
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
    return new NameMatchers<>(NAMED_ONE_OF, new HashSet<>(names));
  }

  /**
   * Matches a {@link NamedElement} for its exact name's absence from a set.
   *
   * @param names The expected names.
   * @param <T> The type of the matched object.
   * @return An element matcher checking if an element's exact name is absent from a set.
   */
  public static <T extends NamedElement> ElementMatcher.Junction<T> namedNoneOf(String... names) {
    return new NameMatchers<>(NAMED_NONE_OF, toSet(names));
  }

  /**
   * Matches a {@link NamedElement} for its name's prefix.
   *
   * @param prefix The expected name's prefix.
   * @param <T> The type of the matched object.
   * @return An element matcher for a named element's name's prefix.
   */
  public static <T extends NamedElement> ElementMatcher.Junction<T> nameStartsWith(String prefix) {
    return deduplicate(NAME_STARTS_WITH, prefix);
  }

  /**
   * Matches a {@link NamedElement} for its name's suffix.
   *
   * @param suffix The expected name's suffix.
   * @param <T> The type of the matched object.
   * @return An element matcher for a named element's name's suffix.
   */
  public static <T extends NamedElement> ElementMatcher.Junction<T> nameEndsWith(String suffix) {
    return deduplicate(NAME_ENDS_WITH, suffix);
  }

  /**
   * Matches a {@link NamedElement} for its exact name.
   *
   * @param name The expected name.
   * @param <T> The type of the matched object.
   * @return An element matcher for a named element's exact name.
   */
  public static <T extends NamedElement> ElementMatcher.Junction<T> named(String name) {
    return deduplicate(NAMED, name);
  }

  @Override
  public boolean matches(T target) {
    String name = target.getActualName();
    switch (mode) {
      case NAMED:
        return name.equals(data);
      case NAME_STARTS_WITH:
        return name.startsWith((String) data);
      case NAME_ENDS_WITH:
        return name.endsWith((String) data);
      case NAMED_ONE_OF:
        return namedOneOf(name);
      case NAMED_NONE_OF:
        return !namedOneOf(name);
      default:
        return false;
    }
  }

  @SuppressWarnings("unchecked")
  private boolean namedOneOf(String name) {
    return ((Set<String>) data).contains(name);
  }

  private static Set<String> toSet(String... strings) {
    return new HashSet<>(Arrays.asList(strings));
  }
}
