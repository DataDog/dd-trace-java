package datadog.opentelemetry.tooling;

import datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/** Replaces OpenTelemetry's {@code AgentElementMatchers} when mapping extensions. */
public final class OtelElementMatchers {

  public static ElementMatcher.Junction<TypeDescription> extendsClass(
      ElementMatcher<TypeDescription> matcher) {
    return HierarchyMatchers.extendsClass(matcher);
  }

  public static ElementMatcher.Junction<TypeDescription> implementsInterface(
      ElementMatcher<TypeDescription> matcher) {
    return HierarchyMatchers.implementsInterface(matcher);
  }

  public static ElementMatcher.Junction<TypeDescription> hasSuperType(
      ElementMatcher<TypeDescription> matcher) {
    return HierarchyMatchers.hasSuperType(matcher);
  }

  public static ElementMatcher.Junction<MethodDescription> methodIsDeclaredByType(
      ElementMatcher<? super TypeDescription> matcher) {
    return ElementMatchers.isDeclaredBy(matcher);
  }

  public static ElementMatcher.Junction<MethodDescription> hasSuperMethod(
      ElementMatcher<? super MethodDescription> matcher) {
    return HierarchyMatchers.hasSuperMethod(matcher);
  }

  public static ElementMatcher.Junction<ClassLoader> hasClassesNamed(String... classNames) {
    return ClassLoaderMatchers.hasClassNamedOneOf(classNames);
  }

  private OtelElementMatchers() {}
}
