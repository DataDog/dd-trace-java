package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ScalaTraitMatchers {
  public static ElementMatcher.Junction<MethodDescription> isTraitMethod(
      String traitName, String name, Object... argumentTypes) {

    ElementMatcher.Junction<MethodDescription> scalaOldArgs =
        isStatic()
            .and(takesArguments(argumentTypes.length + 1))
            .and(takesArgument(0, named(traitName)));
    ElementMatcher.Junction<MethodDescription> scalaNewArgs =
        not(isStatic()).and(takesArguments(argumentTypes.length));

    for (int i = 0; i < argumentTypes.length; i++) {
      Object argumentType = argumentTypes[i];
      ElementMatcher<? super TypeDescription> matcher;
      if (argumentType instanceof ElementMatcher) {
        matcher = (ElementMatcher<? super TypeDescription>) argumentType;
      } else if (argumentType instanceof String) {
        matcher = named((String) argumentType);
      } else if (argumentType instanceof Class) {
        matcher = is((Class<?>) argumentType);
      } else {
        throw new IllegalArgumentException("Unexpected type for argument type specification");
      }
      scalaOldArgs = scalaOldArgs.and(takesArgument(i + 1, matcher));
      scalaNewArgs = scalaNewArgs.and(takesArgument(i, matcher));
    }

    return isMethod().and(named(name)).and(scalaOldArgs.or(scalaNewArgs));
  }
}
