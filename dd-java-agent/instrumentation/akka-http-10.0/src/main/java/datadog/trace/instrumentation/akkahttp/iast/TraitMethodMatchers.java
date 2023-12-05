package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ScalaTraitMatchers.isTraitMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class TraitMethodMatchers {
  public static ElementMatcher.Junction<MethodDescription> isTraitDirectiveMethod(
      String traitName, String name, Object... argumentTypes) {
    return isTraitMethod(traitName, name, (Object[]) argumentTypes)
        .and(returns(named("akka.http.scaladsl.server.Directive")));
  }
}
