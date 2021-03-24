package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.logException;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeAsErasure;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeGetSuperClass;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

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

  /** The matcher to apply to any super type of the matched type. */
  private final ElementMatcher<? super TypeDescription> matcher;

  private final boolean interfacesOnly;
  /**
   * Creates a new matcher for a super type.
   *
   * @param matcher The matcher to apply to any super type of the matched type.
   */
  public SafeHasSuperTypeMatcher(
      final ElementMatcher<? super TypeDescription> matcher, final boolean interfacesOnly) {
    this.matcher = matcher;
    this.interfacesOnly = interfacesOnly;
  }

  @Override
  public boolean matches(final T target) {
    final Set<TypeDescription> checked = new HashSet<>(8);
    // We do not use foreach loop and iterator interface here because we need to catch exceptions
    // in {@code getSuperClass} calls
    TypeDefinition typeDefinition = target;
    while (typeDefinition != null) {
      if (((!interfacesOnly || typeDefinition.isInterface()) && matches(typeDefinition, checked))
          || hasInterface(typeDefinition, checked)) {
        return true;
      }
      typeDefinition = safeGetSuperClass(typeDefinition);
    }
    return false;
  }

  private boolean matches(TypeDefinition typeDefinition, Set<TypeDescription> checked) {
    TypeDescription erasure = safeAsErasure(typeDefinition);
    return null != erasure && checked.add(erasure) && matcher.matches(erasure);
  }

  /**
   * Matches a type's interfaces against the provided matcher.
   *
   * @param typeDefinition The type for which to check all implemented interfaces.
   * @param checked The interfaces that have already been checked.
   * @return {@code true} if any interface matches the supplied matcher.
   */
  private boolean hasInterface(
      final TypeDefinition typeDefinition, final Set<TypeDescription> checked) {
    for (final TypeDefinition interfaceType : safeGetInterfaces(typeDefinition)) {
      if (matches(interfaceType, checked) || hasInterface(interfaceType, checked)) {
        return true;
      }
    }
    return false;
  }

  private Iterable<TypeDefinition> safeGetInterfaces(final TypeDefinition typeDefinition) {
    return new SafeInterfaceIterator(typeDefinition);
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof SafeHasSuperTypeMatcher) {
      return matcher.equals(((SafeHasSuperTypeMatcher) other).matcher);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return matcher.hashCode();
  }

  /**
   * TypeDefinition#getInterfaces() produces an iterator which may throw an exception during
   * iteration if an interface is absent from the classpath.
   *
   * <p>The caller MUST call hasNext() before calling next().
   *
   * <p>This wrapper exists to allow getting interfaces even if the lookup on one fails.
   */
  private static final class SafeInterfaceIterator
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
        logException(typeDefinition, "interfaces", e);
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
          logException(typeDefinition, "interfaces", e);
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
  }
}
