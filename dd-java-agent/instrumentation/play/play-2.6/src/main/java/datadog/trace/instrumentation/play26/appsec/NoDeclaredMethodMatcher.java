package datadog.trace.instrumentation.play26.appsec;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class NoDeclaredMethodMatcher implements ElementMatcher<MethodDescription> {
  private final ElementMatcher<? super MethodDescription> methodMatcher;

  public static ElementMatcher<MethodDescription> hasNoDeclaredMethod(
      ElementMatcher<? super MethodDescription> em) {
    return new NoDeclaredMethodMatcher(em);
  }

  private NoDeclaredMethodMatcher(ElementMatcher<? super MethodDescription> methodMatcher) {
    this.methodMatcher = methodMatcher;
  }

  @Override
  public boolean matches(MethodDescription target) {
    return !target.getDeclaringType().getDeclaredMethods().stream()
        .anyMatch(md -> this.methodMatcher.matches(md));
  }
}
