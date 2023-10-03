package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class TraitMethodMatchers {
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
      } else {
        matcher = named((String) argumentType);
      }
      scalaOldArgs = scalaOldArgs.and(takesArgument(i + 1, matcher));
      scalaNewArgs = scalaNewArgs.and(takesArgument(i, matcher));
    }

    return isMethod().and(named(name)).and(scalaOldArgs.or(scalaNewArgs));
  }

  public static ElementMatcher.Junction<MethodDescription> isTraitDirectiveMethod(
      String traitName, String name, String... argumentTypes) {

    return isTraitMethod(traitName, name, (Object[]) argumentTypes)
        .and(returns(named("akka.http.scaladsl.server.Directive")));
  }
}
