package datadog.trace.instrumentation.pekkohttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class TraitMethodMatchers {
  public static ElementMatcher.Junction<MethodDescription> isTraitDirectiveMethod(
      String traitName, String name, String... argumentTypes) {

    ElementMatcher.Junction<MethodDescription> scalaOldArgs =
        isStatic()
            .and(takesArguments(argumentTypes.length + 1))
            .and(takesArgument(0, named(traitName)));
    ElementMatcher.Junction<MethodDescription> scalaNewArgs =
        not(isStatic()).and(takesArguments(argumentTypes.length));

    for (int i = 0; i < argumentTypes.length; i++) {
      scalaOldArgs = scalaOldArgs.and(takesArgument(i + 1, named(argumentTypes[i])));
      scalaNewArgs = scalaNewArgs.and(takesArgument(i, named(argumentTypes[i])));
    }

    return isMethod()
        .and(named(name))
        .and(returns(named("org.apache.pekko.http.scaladsl.server.Directive")))
        .and(scalaOldArgs.or(scalaNewArgs));
  }
}
