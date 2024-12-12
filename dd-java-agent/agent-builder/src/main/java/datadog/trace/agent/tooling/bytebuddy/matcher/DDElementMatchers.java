package datadog.trace.agent.tooling.bytebuddy.matcher;

import static net.bytebuddy.matcher.ElementMatchers.not;

import datadog.trace.agent.tooling.context.ShouldInjectFieldsMatcher;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * This class provides some custom ByteBuddy element matchers to use when applying instrumentation
 */
public class DDElementMatchers implements HierarchyMatchers.Supplier {
  public static void registerAsSupplier() {
    HierarchyMatchers.registerIfAbsent(new DDElementMatchers());
  }

  @Override
  @SuppressForbidden
  public ElementMatcher.Junction<TypeDescription> declaresAnnotation(
      ElementMatcher<? super NamedElement> matcher) {
    return ElementMatchers.isAnnotatedWith(matcher);
  }

  @Override
  @SuppressForbidden
  public ElementMatcher.Junction<TypeDescription> declaresField(
      ElementMatcher<? super FieldDescription> matcher) {
    return ElementMatchers.declaresField(matcher);
  }

  @Override
  @SuppressForbidden
  public ElementMatcher.Junction<TypeDescription> declaresMethod(
      ElementMatcher<? super MethodDescription> matcher) {
    return ElementMatchers.declaresMethod(matcher);
  }

  @Override
  @SuppressForbidden
  public ElementMatcher.Junction<TypeDescription> concreteClass() {
    return not(ElementMatchers.isAbstract());
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> extendsClass(
      ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, false, true, false);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> implementsInterface(
      ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, true, true, true);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> hasInterface(
      ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, true, false, true);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> hasSuperType(
      ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, false, true, true);
  }

  @Override
  public ElementMatcher.Junction<MethodDescription> hasSuperMethod(
      ElementMatcher<? super MethodDescription> matcher) {
    return new HasSuperMethodMatcher<>(matcher);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> declaresContextField(
      String keyClassName, String contextClassName) {
    return new ShouldInjectFieldsMatcher(keyClassName, contextClassName);
  }

  @SuppressForbidden
  static String safeTypeDefinitionName(final TypeDefinition td) {
    try {
      return td.getTypeName();
    } catch (final IllegalStateException ex) {
      final String message = ex.getMessage();
      if (message.startsWith("Cannot resolve type description for ")) {
        return message.replace("Cannot resolve type description for ", "");
      } else {
        return "?";
      }
    }
  }
}
