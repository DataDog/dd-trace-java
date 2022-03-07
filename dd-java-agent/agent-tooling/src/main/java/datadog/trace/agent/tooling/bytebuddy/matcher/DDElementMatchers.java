package datadog.trace.agent.tooling.bytebuddy.matcher;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This class provides some custom ByteBuddy element matchers to use when applying instrumentation
 */
public class DDElementMatchers {

  public static <T extends TypeDescription> ElementMatcher.Junction<T> extendsClass(
      final ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, false, true, false);
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> implementsInterface(
      final ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, true, true, true);
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> hasInterface(
      final ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, true, false, true);
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> safeHasSuperType(
      final ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, false, true, true);
  }

  // TODO: add javadoc
  public static <T extends MethodDescription> ElementMatcher.Junction<T> hasSuperMethod(
      final ElementMatcher<? super MethodDescription> matcher) {
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
