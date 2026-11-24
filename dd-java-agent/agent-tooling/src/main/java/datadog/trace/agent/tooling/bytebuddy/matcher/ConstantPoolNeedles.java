package datadog.trace.agent.tooling.bytebuddy.matcher;

import static java.util.Collections.singleton;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.AnnotationTypeMatcher;
import net.bytebuddy.matcher.CollectionItemMatcher;
import net.bytebuddy.matcher.DeclaringAnnotationMatcher;
import net.bytebuddy.matcher.DeclaringTypeMatcher;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.EqualityMatcher;
import net.bytebuddy.matcher.ErasureMatcher;
import net.bytebuddy.matcher.MethodSortMatcher;
import net.bytebuddy.matcher.NameMatcher;
import net.bytebuddy.matcher.StringMatcher;

/**
 * Lowers matchers to constant-pool strings a type must contain for them to match.
 *
 * <p>Each result is a necessary condition only: a declared member (or type) can match only if at
 * least one of the returned strings is a {@code CONSTANT_Utf8} entry in the class file. For members
 * these are member names, and descriptors like {@code Ljavax/ws/rs/Path;} for required annotations.
 * {@code null} means the matcher can't be lowered, so callers must not filter on it. Unknown
 * matcher types, negations, and failures while inspecting byte-buddy internals all lower to {@code
 * null}, which keeps lowering sound.
 *
 * <p>A needle ending in {@link #PREFIX} is a prefix: any Utf8 entry starting with the rest of it
 * counts as present. Reading a needle as a prefix only widens it, so it can never cause a miss.
 */
public final class ConstantPoolNeedles {
  /** Marks a needle as a prefix. */
  public static final char PREFIX = '*';

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
  private static final Field DECLARING_ANNOTATION =
      field(DeclaringAnnotationMatcher.class, "matcher");
  private static final Field COLLECTION_ITEM = field(CollectionItemMatcher.class, "matcher");
  private static final Field ANNOTATION_TYPE = field(AnnotationTypeMatcher.class, "matcher");
  private static final Field EQUALITY_VALUE = field(EqualityMatcher.class, "value");
  private static final Field DECLARING_TYPE = field(DeclaringTypeMatcher.class, "matcher");
  private static final Field ERASURE = field(ErasureMatcher.class, "matcher");

  private ConstantPoolNeedles() {}

  /** Lowers matchers on declared members; {@code null} if any of them can't be lowered. */
  public static Set<String> forMembers(Collection<? extends ElementMatcher<?>> matchers) {
    Set<String> needles = new HashSet<>();
    for (ElementMatcher<?> matcher : matchers) {
      Set<String> lowered = forMember(matcher);
      if (null == lowered) {
        return null;
      }
      needles.addAll(lowered);
    }
    return needles;
  }

  /** Lowers a matcher on declared members; {@code null} if it can't be lowered. */
  public static Set<String> forMember(ElementMatcher<?> matcher) {
    try {
      Lowered lowered = lowerMember(matcher);
      return null != lowered ? lowered.needles : null;
    } catch (Throwable e) {
      return null; // can't inspect this matcher, so don't filter on it
    }
  }

  /**
   * Lowers a matcher on a type's name (e.g. an annotation type) to the binary names it accepts;
   * {@code null} if it can't be lowered.
   */
  public static Set<String> forTypeName(ElementMatcher<?> matcher) {
    try {
      return lowerName(matcher);
    } catch (Throwable e) {
      return null;
    }
  }

  /**
   * For {@code isDeclaredBy(X)} (optionally through {@code erasure}), returns X; else {@code null}.
   * On a type's own members the declaring type is the type itself, so X applies to that type.
   */
  public static ElementMatcher<?> declaringTypeMatcher(ElementMatcher<?> matcher) {
    try {
      if (matcher instanceof DeclaringTypeMatcher && null != DECLARING_TYPE) {
        Object inner = DECLARING_TYPE.get(matcher);
        if (inner instanceof ErasureMatcher && null != ERASURE) {
          inner = ERASURE.get(inner);
        }
        return inner instanceof ElementMatcher ? (ElementMatcher<?>) inner : null;
      }
    } catch (Throwable ignored) {
      // can't inspect it
    }
    return null;
  }

  private static boolean hasPrefix(Set<String> needles) {
    for (String needle : needles) {
      if (!needle.isEmpty() && needle.charAt(needle.length() - 1) == PREFIX) {
        return true;
      }
    }
    return false;
  }

  /** Lowered needles; {@code byName} means they're names, of which an element has exactly one. */
  private static final class Lowered {
    final Set<String> needles;
    final boolean byName;

    Lowered(Set<String> needles, boolean byName) {
      this.needles = needles;
      this.byName = byName;
    }
  }

  @SuppressWarnings("unchecked")
  private static Lowered lowerMember(ElementMatcher<?> matcher) throws Exception {
    if (matcher instanceof MethodSortMatcher && null != METHOD_SORT) {
      String sort = String.valueOf(METHOD_SORT.get(matcher));
      if ("CONSTRUCTOR".equals(sort)) {
        return new Lowered(singleton(CONSTRUCTOR), true);
      }
      if ("TYPE_INITIALIZER".equals(sort)) {
        return new Lowered(singleton(TYPE_INITIALIZER), true);
      }
      return null; // e.g. isMethod(): any method name
    }
    if (matcher instanceof DeclaringAnnotationMatcher && null != DECLARING_ANNOTATION) {
      // annotated with a named type: its descriptor must be in the constant pool
      Set<String> typeNames = annotationTypeNames(DECLARING_ANNOTATION.get(matcher));
      if (null == typeNames) {
        return null;
      }
      Set<String> descriptors = new HashSet<>();
      for (String typeName : typeNames) {
        String internal = typeName.replace('.', '/');
        // a prefix of a type name is a prefix of its descriptor, without the closing ';'
        descriptors.add(hasPrefix(singleton(typeName)) ? 'L' + internal : 'L' + internal + ';');
      }
      return new Lowered(descriptors, false);
    }
    if (matcher instanceof ElementMatcher.Junction.Conjunction && null != JUNCTION_MATCHERS_AND) {
      // every part must match: names (single-valued) can be intersected; otherwise any one part's
      // needles are a valid condition, so pick the smallest set
      Set<String> names = null;
      Set<String> best = null;
      for (ElementMatcher<?> part : (List<ElementMatcher<?>>) JUNCTION_MATCHERS_AND.get(matcher)) {
        Lowered lowered = lowerMember(part);
        if (null == lowered) {
          continue;
        }
        if (lowered.byName) {
          if (null == names) {
            names = new HashSet<>(lowered.needles);
          } else {
            names.retainAll(lowered.needles);
          }
        } else if (null == best || lowered.needles.size() < best.size()) {
          best = lowered.needles;
        }
      }
      if (null != names && (null == best || names.size() <= best.size())) {
        return new Lowered(names, true);
      }
      return null != best ? new Lowered(best, false) : null;
    }
    if (matcher instanceof ElementMatcher.Junction.Disjunction && null != JUNCTION_MATCHERS_OR) {
      // any part may match, so the needles of every part; one unconstrained part frees all
      Set<String> needles = new HashSet<>();
      boolean byName = true;
      for (ElementMatcher<?> part : (List<ElementMatcher<?>>) JUNCTION_MATCHERS_OR.get(matcher)) {
        Lowered lowered = lowerMember(part);
        if (null == lowered) {
          return null;
        }
        needles.addAll(lowered.needles);
        byName &= lowered.byName;
      }
      return new Lowered(needles, byName);
    }
    Set<String> names = lowerName(matcher);
    return null != names ? new Lowered(names, !hasPrefix(names)) : null;
  }

  /** Names accepted by a matcher on an element's name; {@code null} if not a name matcher. */
  @SuppressWarnings("unchecked")
  private static Set<String> lowerName(ElementMatcher<?> matcher) throws Exception {
    if (matcher instanceof NameMatchers.Named) {
      return singleton(((NameMatchers.Named<?>) matcher).name);
    }
    if (matcher instanceof NameMatchers.OneOf) {
      return ((NameMatchers.OneOf<?>) matcher).names;
    }
    if (matcher instanceof NameMatchers.StartsWith) {
      return singleton(((NameMatchers.StartsWith<?>) matcher).name + PREFIX);
    }
    if (matcher instanceof EqualityMatcher && null != EQUALITY_VALUE) {
      Object value = EQUALITY_VALUE.get(matcher);
      // a type is equal to the given type only if it has the same name
      return value instanceof TypeDescription
          ? singleton(((TypeDescription) value).getName())
          : null;
    }
    if (matcher instanceof NameMatcher && null != NAME_MATCHER) {
      Object stringMatcher = NAME_MATCHER.get(matcher);
      if (stringMatcher instanceof StringMatcher
          && null != STRING_MODE
          && STRING_MODE.get(stringMatcher) == StringMatcher.Mode.EQUALS_FULLY) {
        return singleton((String) STRING_VALUE.get(stringMatcher));
      }
      if (stringMatcher instanceof StringMatcher
          && null != STRING_MODE
          && STRING_MODE.get(stringMatcher) == StringMatcher.Mode.STARTS_WITH) {
        return singleton(STRING_VALUE.get(stringMatcher) + String.valueOf(PREFIX));
      }
      return null;
    }
    if (matcher instanceof ElementMatcher.Junction.Conjunction && null != JUNCTION_MATCHERS_AND) {
      // exact names can be intersected; prefixes can't, so fall back to the smallest set
      Set<String> exact = null;
      Set<String> prefixed = null;
      for (ElementMatcher<?> part : (List<ElementMatcher<?>>) JUNCTION_MATCHERS_AND.get(matcher)) {
        Set<String> lowered = lowerName(part);
        if (null == lowered) {
          continue;
        }
        if (hasPrefix(lowered)) {
          if (null == prefixed || lowered.size() < prefixed.size()) {
            prefixed = lowered;
          }
        } else if (null == exact) {
          exact = new HashSet<>(lowered);
        } else {
          exact.retainAll(lowered);
        }
      }
      return null != exact ? exact : prefixed;
    }
    if (matcher instanceof ElementMatcher.Junction.Disjunction && null != JUNCTION_MATCHERS_OR) {
      Set<String> names = new HashSet<>();
      for (ElementMatcher<?> part : (List<ElementMatcher<?>>) JUNCTION_MATCHERS_OR.get(matcher)) {
        Set<String> lowered = lowerName(part);
        if (null == lowered) {
          return null;
        }
        names.addAll(lowered);
      }
      return names;
    }
    return null;
  }

  /** Type names from {@code whereOne(ofAnnotationType(<name matcher>))}, else {@code null}. */
  private static Set<String> annotationTypeNames(Object annotationListMatcher) throws Exception {
    if (annotationListMatcher instanceof CollectionItemMatcher && null != COLLECTION_ITEM) {
      Object annotationMatcher = COLLECTION_ITEM.get(annotationListMatcher);
      if (annotationMatcher instanceof AnnotationTypeMatcher && null != ANNOTATION_TYPE) {
        return lowerName((ElementMatcher<?>) ANNOTATION_TYPE.get(annotationMatcher));
      }
    }
    return null;
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
