package datadog.trace.agent.tooling.bytebuddy.matcher;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import net.bytebuddy.description.annotation.AnnotationSource;
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
  public <T extends TypeDescription> ElementMatcher.Junction<T> extendsClass(
      ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, false, true, false);
  }

  @Override
  public <T extends TypeDescription> ElementMatcher.Junction<T> implementsInterface(
      ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, true, true, true);
  }

  @Override
  @SuppressForbidden
  public <T extends AnnotationSource> ElementMatcher.Junction<T> isAnnotatedWith(
      ElementMatcher<? super TypeDescription> matcher) {
    return ElementMatchers.isAnnotatedWith(matcher);
  }

  @Override
  @SuppressForbidden
  public <T extends TypeDescription> ElementMatcher.Junction<T> declaresField(
      ElementMatcher<? super FieldDescription> matcher) {
    return ElementMatchers.declaresField(matcher);
  }

  @Override
  @SuppressForbidden
  public <T extends TypeDescription> ElementMatcher.Junction<T> declaresMethod(
      ElementMatcher<? super MethodDescription> matcher) {
    return ElementMatchers.declaresMethod(matcher);
  }

  @Override
  public <T extends TypeDescription> ElementMatcher.Junction<T> hasInterface(
      ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, true, false, true);
  }

  @Override
  public <T extends TypeDescription> ElementMatcher.Junction<T> hasSuperType(
      ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, false, true, true);
  }

  @Override
  public <T extends MethodDescription> ElementMatcher.Junction<T> hasSuperMethod(
      ElementMatcher<? super MethodDescription> matcher) {
    return new HasSuperMethodMatcher<>(matcher);
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
