package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.SafeHasSuperTypeMatcher.safeGetSuperClass;
import static net.bytebuddy.matcher.ElementMatchers.hasSignature;

import java.util.HashSet;
import java.util.Set;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.matcher.ElementMatcher;

// TODO: add javadoc
class HasSuperMethodMatcher<T extends MethodDescription>
    extends ElementMatcher.Junction.AbstractBase<T> {

  private final ElementMatcher<? super MethodDescription> matcher;

  public HasSuperMethodMatcher(final ElementMatcher<? super MethodDescription> matcher) {
    this.matcher = matcher;
  }

  @Override
  public boolean matches(final MethodDescription target) {
    if (target.isConstructor()) {
      return false;
    }
    final Junction<MethodDescription> signatureMatcher = hasSignature(target.asSignatureToken());
    TypeDefinition declaringType = target.getDeclaringType();
    final Set<TypeDefinition> checkedInterfaces = new HashSet<>();

    while (declaringType != null) {
      for (final MethodDescription methodDescription : declaringType.getDeclaredMethods()) {
        if (signatureMatcher.matches(methodDescription) && matcher.matches(methodDescription)) {
          return true;
        }
      }
      if (matchesInterface(declaringType.getInterfaces(), signatureMatcher, checkedInterfaces)) {
        return true;
      }
      declaringType = safeGetSuperClass(declaringType);
    }
    return false;
  }

  private boolean matchesInterface(
      final TypeList.Generic interfaces,
      final Junction<MethodDescription> signatureMatcher,
      final Set<TypeDefinition> checkedInterfaces) {
    for (final TypeDefinition type : interfaces) {
      if (!checkedInterfaces.contains(type)) {
        checkedInterfaces.add(type);
        for (final MethodDescription methodDescription : type.getDeclaredMethods()) {
          if (signatureMatcher.matches(methodDescription) && matcher.matches(methodDescription)) {
            return true;
          }
        }
        if (matchesInterface(type.getInterfaces(), signatureMatcher, checkedInterfaces)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public String toString() {
    return "hasSuperMethodMatcher(" + matcher + ")";
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
      return matcher.equals(((HasSuperMethodMatcher) other).matcher);
    }
  }

  @Override
  public int hashCode() {
    return 17 * 31 + matcher.hashCode();
  }
}
