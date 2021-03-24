package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeAsErasure;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeGetSuperClass;

import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

// TODO: add javadoc
class SafeExtendsClassMatcher<T extends TypeDescription>
    extends ElementMatcher.Junction.AbstractBase<T> {

  private final ElementMatcher<? super TypeDescription> matcher;

  public SafeExtendsClassMatcher(final ElementMatcher<? super TypeDescription> matcher) {
    this.matcher = matcher;
  }

  @Override
  public boolean matches(final T target) {
    // We do not use foreach loop and iterator interface here because we need to catch exceptions
    // in {@code getSuperClass} calls
    TypeDefinition typeDefinition = target;
    while (typeDefinition != null) {
      if (matches(typeDefinition)) {
        return true;
      }
      typeDefinition = safeGetSuperClass(typeDefinition);
    }
    return false;
  }

  private boolean matches(TypeDefinition typeDefinition) {
    TypeDescription erasure = safeAsErasure(typeDefinition);
    return null != erasure && matcher.matches(erasure);
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof SafeExtendsClassMatcher) {
      return matcher.equals(((SafeExtendsClassMatcher) other).matcher);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return matcher.hashCode();
  }
}
