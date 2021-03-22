package datadog.trace.agent.tooling.bytebuddy.matcher;

import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.not;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides some custom ByteBuddy element matchers to use when applying instrumentation
 */
public final class DDElementMatchers {

  private static final Logger log = LoggerFactory.getLogger(DDElementMatchers.class);

  public static <T extends TypeDescription> ElementMatcher.Junction<T> extendsClass(
      final ElementMatcher<? super TypeDescription> matcher) {
    return not(isInterface()).and(new SafeExtendsClassMatcher<>(matcher));
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> implementsInterface(
      final ElementMatcher<? super TypeDescription> matcher) {
    return not(isInterface()).and(new SafeHasSuperTypeMatcher<>(matcher, true));
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> hasInterface(
      final ElementMatcher<? super TypeDescription> matcher) {
    return new SafeHasSuperTypeMatcher<>(matcher, true);
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> safeHasSuperType(
      final ElementMatcher<? super TypeDescription> matcher) {
    return not(isInterface()).and(new SafeHasSuperTypeMatcher<>(matcher, false));
  }

  // TODO: add javadoc
  public static <T extends MethodDescription> ElementMatcher.Junction<T> hasSuperMethod(
      final ElementMatcher<? super MethodDescription> matcher) {
    return new HasSuperMethodMatcher<>(matcher);
  }

  /**
   * Wraps another matcher to assure that an element is not matched in case that the matching causes
   * an {@link Exception}. Logs exception if it happens.
   *
   * @param matcher The element matcher that potentially throws an exception.
   * @param <T> The type of the matched object.
   * @return A matcher that returns {@code false} in case that the given matcher throws an
   *     exception.
   */
  public static <T> ElementMatcher.Junction<T> failSafe(
      final ElementMatcher<? super T> matcher, final String description) {
    return new LoggingFailSafeMatcher<>(matcher, false, description);
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

  static TypeDescription safeAsErasure(final TypeDefinition typeDefinition) {
    try {
      return typeDefinition.asErasure();
    } catch (final Exception e) {
      if (log.isDebugEnabled()) {
        log.debug(
            "{} trying to get erasure for target {}: {}",
            e.getClass().getSimpleName(),
            safeTypeDefinitionName(typeDefinition),
            e.getMessage());
      }
      return null;
    }
  }
}
