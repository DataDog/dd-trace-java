package datadog.trace.agent.tooling.bytebuddy.matcher;

import static java.util.Collections.singleton;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.MethodSortMatcher;
import net.bytebuddy.matcher.NameMatcher;
import net.bytebuddy.matcher.StringMatcher;

/**
 * Lowers method matchers to the method names a type must declare for them to match.
 *
 * <p>The result is a necessary condition only: a method can match only if its name is in the
 * returned set. {@code null} means the matcher could not be lowered (any name may match), so
 * callers must not filter on it. Unknown matcher types, negations, and failures while inspecting
 * byte-buddy internals all lower to {@code null}, which keeps lowering sound.
 */
public final class DeclaredMethodNames {
  private static final String CONSTRUCTOR = "<init>";
  private static final String TYPE_INITIALIZER = "<clinit>";

  private static final Field JUNCTION_MATCHERS_AND =
      field(ElementMatcher.Junction.Conjunction.class, "matchers");
  private static final Field JUNCTION_MATCHERS_OR =
      field(ElementMatcher.Junction.Disjunction.class, "matchers");
  private static final Field NAME_MATCHER = field(NameMatcher.class, "matcher");
  private static final Field STRING_VALUE = field(StringMatcher.class, "value");
  private static final Field STRING_MODE = field(StringMatcher.class, "mode");
  private static final Field METHOD_SORT = field(MethodSortMatcher.class, "sort");

  private DeclaredMethodNames() {}

  /** Lowers all the given matchers; {@code null} if any of them can't be lowered. */
  public static Set<String> lowerAll(Collection<? extends ElementMatcher<?>> matchers) {
    Set<String> names = new HashSet<>();
    for (ElementMatcher<?> matcher : matchers) {
      Set<String> lowered = lower(matcher);
      if (null == lowered) {
        return null;
      }
      names.addAll(lowered);
    }
    return names;
  }

  /** Lowers a single matcher; {@code null} if it can't be lowered. */
  public static Set<String> lower(ElementMatcher<?> matcher) {
    try {
      return doLower(matcher);
    } catch (Throwable e) {
      return null; // can't inspect this matcher, so don't filter on it
    }
  }

  @SuppressWarnings("unchecked")
  private static Set<String> doLower(ElementMatcher<?> matcher) throws Exception {
    if (matcher instanceof NameMatchers.Named) {
      return singleton(((NameMatchers.Named<?>) matcher).name);
    }
    if (matcher instanceof NameMatchers.OneOf) {
      return ((NameMatchers.OneOf<?>) matcher).names;
    }
    if (matcher instanceof NameMatcher && null != NAME_MATCHER) {
      Object stringMatcher = NAME_MATCHER.get(matcher);
      if (stringMatcher instanceof StringMatcher
          && null != STRING_MODE
          && STRING_MODE.get(stringMatcher) == StringMatcher.Mode.EQUALS_FULLY) {
        return singleton((String) STRING_VALUE.get(stringMatcher));
      }
      return null;
    }
    if (matcher instanceof MethodSortMatcher && null != METHOD_SORT) {
      String sort = String.valueOf(METHOD_SORT.get(matcher));
      if ("CONSTRUCTOR".equals(sort)) {
        return singleton(CONSTRUCTOR);
      }
      if ("TYPE_INITIALIZER".equals(sort)) {
        return singleton(TYPE_INITIALIZER);
      }
      return null; // e.g. isMethod(): any method name
    }
    if (matcher instanceof ElementMatcher.Junction.Conjunction && null != JUNCTION_MATCHERS_AND) {
      // every part must match, so the name must be in every part that constrains names
      Set<String> names = null;
      for (ElementMatcher<?> part : (List<ElementMatcher<?>>) JUNCTION_MATCHERS_AND.get(matcher)) {
        Set<String> lowered = doLower(part);
        if (null != lowered) {
          if (null == names) {
            names = new HashSet<>(lowered);
          } else {
            names.retainAll(lowered);
          }
        }
      }
      return names;
    }
    if (matcher instanceof ElementMatcher.Junction.Disjunction && null != JUNCTION_MATCHERS_OR) {
      // any part may match, so the name may be in any part; one unconstrained part frees all
      Set<String> names = new HashSet<>();
      for (ElementMatcher<?> part : (List<ElementMatcher<?>>) JUNCTION_MATCHERS_OR.get(matcher)) {
        Set<String> lowered = doLower(part);
        if (null == lowered) {
          return null;
        }
        names.addAll(lowered);
      }
      return names;
    }
    return null; // negations and anything else: no constraint on the name
  }

  private static Field field(Class<?> type, String name) {
    try {
      Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      return field;
    } catch (Throwable e) {
      return null; // byte-buddy internals changed; lowering of this kind is disabled
    }
  }
}
