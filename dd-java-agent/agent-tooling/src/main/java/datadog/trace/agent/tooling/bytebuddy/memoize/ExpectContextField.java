package datadog.trace.agent.tooling.bytebuddy.memoize;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

final class ExpectContextField extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
  private final ElementMatcher<TypeDescription> typeMatcher;

  ExpectContextField(ElementMatcher<TypeDescription> typeMatcher) {
    this.typeMatcher = typeMatcher;
  }

  @Override
  protected boolean doMatch(TypeDescription target) {
    return hasContextStore(target) && !hasContextStore(target.getSuperClass().asErasure());
  }

  boolean hasContextStore(TypeDescription target) {
    return typeMatcher.matches(target);
  }
}
