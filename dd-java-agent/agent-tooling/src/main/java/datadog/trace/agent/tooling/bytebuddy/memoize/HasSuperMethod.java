package datadog.trace.agent.tooling.bytebuddy.memoize;

import static net.bytebuddy.matcher.ElementMatchers.hasSignature;

import java.util.HashSet;
import java.util.Set;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.matcher.ElementMatcher;

final class HasSuperMethod extends ElementMatcher.Junction.ForNonNullValues<MethodDescription> {

  private final ElementMatcher<TypeDescription> typeMatcher;
  private final ElementMatcher<? super MethodDescription> methodMatcher;

  HasSuperMethod(
      ElementMatcher<TypeDescription> typeMatcher,
      ElementMatcher<? super MethodDescription> methodMatcher) {
    this.typeMatcher = typeMatcher;
    this.methodMatcher = methodMatcher;
  }

  @Override
  protected boolean doMatch(MethodDescription target) {
    if (target.isConstructor()) {
      return false;
    }

    TypeDefinition type = target.getDeclaringType();
    if (!typeMatcher.matches(type.asErasure())) {
      return false;
    } else if (methodMatcher.matches(target)) {
      return true;
    }

    ElementMatcher<MethodDescription> signatureMatcher = hasSignature(target.asSignatureToken());
    Set<String> visited = new HashSet<>();

    if (interfaceMatches(type.getInterfaces(), signatureMatcher, visited)) {
      return true;
    }

    type = type.getSuperClass();
    while (null != type && typeMatcher.matches(type.asErasure())) {
      for (MethodDescription method : type.getDeclaredMethods()) {
        if (signatureMatcher.matches(method) && methodMatcher.matches(method)) {
          return true;
        }
      }
      if (interfaceMatches(type.getInterfaces(), signatureMatcher, visited)) {
        return true;
      }
      type = type.getSuperClass();
    }
    return false;
  }

  private boolean interfaceMatches(
      TypeList.Generic interfaces,
      ElementMatcher<MethodDescription> signatureMatcher,
      Set<String> visited) {
    for (TypeDefinition type : interfaces) {
      if (!visited.add(type.getTypeName()) || !typeMatcher.matches(type.asErasure())) {
        continue;
      }
      for (MethodDescription method : type.getDeclaredMethods()) {
        if (signatureMatcher.matches(method) && methodMatcher.matches(method)) {
          return true;
        }
      }
      if (interfaceMatches(type.getInterfaces(), signatureMatcher, visited)) {
        return true;
      }
    }
    return false;
  }
}
