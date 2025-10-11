package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeTypeDefinitionName;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
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
    extends ElementMatcher.Junction.ForNonNullValues<T> {

  private static final Logger log = LoggerFactory.getLogger(SafeHasSuperTypeMatcher.class);

  /** The matcher to apply to any super type of the matched type. */
  private final ElementMatcher<? super TypeDescription> matcher;

  private final boolean interfacesOnly;
  private final boolean rejectInterfaceTargets;
  private final boolean checkInterfaces;

  /**
   * Creates a new matcher for a super type.
   *
   * @param matcher The matcher to apply to any super type of the matched type.
   */
  public SafeHasSuperTypeMatcher(
      ElementMatcher<? super TypeDescription> matcher,
      boolean interfacesOnly,
      boolean rejectInterfaceTargets,
      boolean checkInterfaces) {
    this.matcher = matcher;
    this.interfacesOnly = interfacesOnly;
    this.rejectInterfaceTargets = rejectInterfaceTargets;
    this.checkInterfaces = checkInterfaces;
  }

  @Override
  protected boolean doMatch(final T target) {
    boolean isInterface = safeIsInterface(target);
    if (rejectInterfaceTargets && isInterface) {
      return false;
    }
    // We do not use foreach loop and iterator interface here because we need to catch exceptions
    // in {@code getSuperClass} calls
    TypeDefinition typeDefinition = target;
    if (checkInterfaces) {
      final Set<TypeDescription> checkedInterfaces = new HashSet<>(8);
      while (typeDefinition != null) {
        if (((!interfacesOnly || isInterface) && erasureMatches(typeDefinition.asGenericType()))
            || (hasInterface(typeDefinition, checkedInterfaces))) {
          return true;
        }
        typeDefinition = safeGetSuperClass(typeDefinition);
      }
    } else {
      while (typeDefinition != null) {
        if (erasureMatches(typeDefinition.asGenericType())) {
          return true;
        }
        typeDefinition = safeGetSuperClass(typeDefinition);
      }
    }
    return false;
  }

  private boolean erasureMatches(TypeDescription.Generic typeDefinition) {
    TypeDescription erasure = safeAsErasure(typeDefinition);
    return null != erasure && matcher.matches(erasure);
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
            && (erasureMatches(interfaceType.asGenericType())
                || hasInterface(interfaceType, checkedInterfaces))) {
          return true;
        }
      }
    }
    return false;
  }

  private Iterable<TypeDefinition> safeGetInterfaces(final TypeDefinition typeDefinition) {
    return new SafeInterfaceIterator(typeDefinition);
  }

  static boolean safeIsInterface(final TypeDefinition typeDefinition) {
    try {
      return typeDefinition.isInterface();
    } catch (final Exception e) {
      if (log.isDebugEnabled()) {
        log.debug(
            "{} trying to check isInterface for target {}: {}",
            e.getClass().getSimpleName(),
            safeTypeDefinitionName(typeDefinition),
            e.getMessage());
      }
      return false;
    }
  }

  static TypeDefinition safeGetSuperClass(final TypeDefinition typeDefinition) {
    try {
      return typeDefinition.getSuperClass();
    } catch (final Exception e) {
      if (log.isDebugEnabled()) {
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
   *
   * <p>The caller MUST call hasNext() before calling next().
   *
   * <p>This wrapper exists to allow getting interfaces even if the lookup on one fails.
   */
  private static class SafeInterfaceIterator
      implements Iterator<TypeDefinition>, Iterable<TypeDefinition> {
    private final TypeDefinition typeDefinition;
    private final Iterator<TypeDescription.Generic> it;
    private TypeDefinition next;

    private SafeInterfaceIterator(TypeDefinition typeDefinition) {
      this.typeDefinition = typeDefinition;
      Iterator<TypeDescription.Generic> it = null;
      try {
        it = typeDefinition.getInterfaces().iterator();
      } catch (Exception e) {
        logException(typeDefinition, e);
      }
      this.it = it;
    }

    @Override
    public boolean hasNext() {
      if (null != it && it.hasNext()) {
        try {
          this.next = it.next();
          return true;
        } catch (Exception e) {
          logException(typeDefinition, e);
          return false;
        }
      }
      return false;
    }

    @Override
    @SuppressFBWarnings("IT_NO_SUCH_ELEMENT")
    public TypeDefinition next() {
      return next;
    }

    @Override
    public void remove() {}

    @Override
    public Iterator<TypeDefinition> iterator() {
      return this;
    }

    private void logException(TypeDefinition typeDefinition, Exception e) {
      if (log.isDebugEnabled()) {
        log.debug(
            "{} trying to get interfaces for target {}: {}",
            e.getClass().getSimpleName(),
            safeTypeDefinitionName(typeDefinition),
            e.getMessage());
      }
    }
  }

  static TypeDescription safeAsErasure(final TypeDefinition typeDefinition) {
    try {
      return typeDefinition.asErasure();
    } catch (final Exception e) {
      if (log.isDebugEnabled()) {
        log.debug(
            "{} trying to get erasure for target {}: {}",
            e.getClass().getSimpleName(),
            safeTypeDefinitionName(typeDefinition),
            e.getMessage());
      }
      return null;
    }
  }
}
