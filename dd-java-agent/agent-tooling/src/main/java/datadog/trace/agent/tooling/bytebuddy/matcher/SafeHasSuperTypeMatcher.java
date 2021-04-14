package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeTypeDefinitionName;
import static datadog.trace.agent.tooling.bytebuddy.matcher.SafeErasureMatcher.safeAsErasure;

import datadog.trace.agent.tooling.AgentTooling;
import datadog.trace.api.Function;
import datadog.trace.bootstrap.WeakCache;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An element matcher that matches a super type. This is different from {@link
 * net.bytebuddy.matcher.HasSuperTypeMatcher} in the following way:
 *
 * <ul>
 *   <li>Exceptions are logged
 *   <li>When exception happens the rest of the inheritance subtree is discarded (since ByteBuddy
 *       cannot load/parse type information for it) but search in other subtrees continues
 * </ul>
 *
 * <p>This is useful because this allows us to see when matcher's check is not complete (i.e. part
 * of it fails), at the same time it makes best effort instead of failing quickly (like {@code
 * failSafe(hasSuperType(...))} does) which means the code is more resilient to classpath
 * inconsistencies
 *
 * @param <T> The type of the matched entity.
 * @see net.bytebuddy.matcher.HasSuperTypeMatcher
 */
class SafeHasSuperTypeMatcher<T extends TypeDescription>
    extends ElementMatcher.Junction.AbstractBase<T> {

  private static final Logger log = LoggerFactory.getLogger(SafeHasSuperTypeMatcher.class);

  private static final boolean DEBUG = log.isDebugEnabled();

  // this cache aims to prevent multiple matchers applied to the same type from
  // repeating interfaces lookups, with some extra space to spread the cost of
  // expunging over several loaded types. To ensure we retain commonly looked up
  // types, we would need a smarter LRU cache
  private static final WeakCache<TypeDefinition, List<TypeDefinition>> INTERFACES_CACHE =
      AgentTooling.newWeakCache(512);
  private static final CachedInterfacesLookup LOOKUP = new CachedInterfacesLookup();
  private static final List<TypeDefinition> EMPTY = new ArrayList<>(0);

  /** The matcher to apply to any super type of the matched type. */
  private final ElementMatcher<? super TypeDescription.Generic> matcher;

  private final boolean interfacesOnly;
  /**
   * Creates a new matcher for a super type.
   *
   * @param matcher The matcher to apply to any super type of the matched type.
   */
  public SafeHasSuperTypeMatcher(
      final ElementMatcher<? super TypeDescription.Generic> matcher, final boolean interfacesOnly) {
    this.matcher = matcher;
    this.interfacesOnly = interfacesOnly;
  }

  @Override
  public boolean matches(final T target) {
    final Set<TypeDescription> checkedInterfaces = new HashSet<>(8);
    // We do not use foreach loop and iterator interface here because we need to catch exceptions
    // in {@code getSuperClass} calls
    TypeDefinition typeDefinition = target;
    while (typeDefinition != null) {
      if (((!interfacesOnly || typeDefinition.isInterface())
              && matcher.matches(typeDefinition.asGenericType()))
          || hasInterface(typeDefinition, checkedInterfaces)) {
        return true;
      }
      typeDefinition = safeGetSuperClass(typeDefinition);
    }
    return false;
  }

  /**
   * Matches a type's interfaces against the provided matcher.
   *
   * @param typeDefinition The type for which to check all implemented interfaces.
   * @param checkedInterfaces The interfaces that have already been checked.
   * @return {@code true} if any interface matches the supplied matcher.
   */
  private boolean hasInterface(
      final TypeDefinition typeDefinition, final Set<TypeDescription> checkedInterfaces) {
    for (final TypeDefinition interfaceType : safeGetInterfaces(typeDefinition)) {
      final TypeDescription erasure = safeAsErasure(interfaceType);
      if (erasure != null) {
        if (checkedInterfaces.add(interfaceType.asErasure())
            && (matcher.matches(interfaceType.asGenericType())
                || hasInterface(interfaceType, checkedInterfaces))) {
          return true;
        }
      }
    }
    return false;
  }

  private Iterable<TypeDefinition> safeGetInterfaces(final TypeDefinition typeDefinition) {
    return INTERFACES_CACHE.computeIfAbsent(typeDefinition, LOOKUP);
  }

  static TypeDefinition safeGetSuperClass(final TypeDefinition typeDefinition) {
    try {
      return typeDefinition.getSuperClass();
    } catch (final Exception e) {
      if (DEBUG) {
        log.debug(
            "{} trying to get super class for target {}: {}",
            e.getClass().getSimpleName(),
            safeTypeDefinitionName(typeDefinition),
            e.getMessage());
      }
      return null;
    }
  }

  @Override
  public String toString() {
    return "safeHasSuperType(" + matcher + ")";
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    } else if (other == null) {
      return false;
    } else if (getClass() != other.getClass()) {
      return false;
    } else {
      return matcher.equals(((SafeHasSuperTypeMatcher) other).matcher);
    }
  }

  @Override
  public int hashCode() {
    return 17 * 31 + matcher.hashCode();
  }

  /**
   * TypeDefinition#getInterfaces() produces an iterator which may throw an exception during
   * iteration if an interface is absent from the classpath.
   */
  private static final class CachedInterfacesLookup
      implements Function<TypeDefinition, List<TypeDefinition>> {
    @Override
    public List<TypeDefinition> apply(TypeDefinition input) {
      try {
        TypeList.Generic interfaces = input.getInterfaces();
        List<TypeDefinition> definitions = new ArrayList<>(interfaces.size());
        Iterator<TypeDescription.Generic> it = interfaces.iterator();
        while (it.hasNext()) {
          try {
            definitions.add(it.next());
          } catch (Exception e) {
            logException(input, e);
          }
        }
        return definitions;
      } catch (Exception e) {
        logException(input, e);
      }
      return EMPTY;
    }
  }

  private static void logException(TypeDefinition typeDefinition, Exception e) {
    if (DEBUG) {
      log.debug(
          "{} trying to get interfaces for target {}: {}",
          e.getClass().getSimpleName(),
          safeTypeDefinitionName(typeDefinition),
          e.getMessage());
    }
  }
}
