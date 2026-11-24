package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.pekko.http.scaladsl.server.RequestContext;
import org.apache.pekko.http.scaladsl.server.RouteResult;
import scala.Function1;
import scala.concurrent.Future;

/** Isolates directive execution until its route result is known. */
public final class PekkoHttpDirectiveInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  @Override
  public String hierarchyMarkerType() {
    return "org.apache.pekko.http.scaladsl.server.Directive";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("tapply")
            .and(takesArguments(1))
            .and(takesArgument(0, named("scala.Function1")))
            .and(returns(named("scala.Function1")))
            .and(not(isBridge())),
        getClass().getName() + "$RouteAdvice");
  }

  public static final class RouteAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.Return(readOnly = false) Function1<RequestContext, Future<RouteResult>> route) {
      route = PekkoHttpRouteWrapper.wrap(route);
    }
  }
}
