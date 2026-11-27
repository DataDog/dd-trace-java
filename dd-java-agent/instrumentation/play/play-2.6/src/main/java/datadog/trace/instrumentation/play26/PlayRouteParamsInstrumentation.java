package datadog.trace.instrumentation.play26;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.appsec.route.NormalizedRouteHelper;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import play.api.mvc.RequestHeader;
import play.core.routing.RouteParams;
import scala.Option;

/**
 * Captures the path parameters Play's router bound for the matched route, so the normalized route
 * tag can tell which parts were present. Only the matched route returns {@code Some}, so the last
 * capture for a request wins.
 *
 * @see play.core.routing.Route.ParamsExtractor#unapply(play.api.mvc.RequestHeader)
 */
@AutoService(InstrumenterModule.class)
public final class PlayRouteParamsInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public PlayRouteParamsInstrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play26Plus";
  }

  @Override
  public String hierarchyMarkerType() {
    return "play.core.routing.Route$ParamsExtractor";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(PlayRouteParams.REQUEST_TARGET, PlayRouteParams.BOUND_VALUES);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("unapply")
            .and(not(isStatic()))
            .and(takesArguments(1))
            .and(takesArgument(0, named("play.api.mvc.RequestHeader")))
            .and(returns(named("scala.Option"))),
        PlayRouteParamsInstrumentation.class.getName() + "$UnapplyAdvice");
  }

  public static class UnapplyAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(
        @Advice.Argument(0) final RequestHeader request,
        @Advice.Return final Option<RouteParams> result) {
      // runs for every route tried: store Play's own map as is, converted only when tagging
      if (result == null || result.isEmpty() || !NormalizedRouteHelper.isEnabled()) {
        return;
      }
      final scala.collection.immutable.Map<String, ?> path = result.get().path();
      // no path params: the route renders statically and the decorator never reads the store
      if (path.isEmpty()) {
        return;
      }
      InstrumentationContext.get(
              "play.api.mvc.request.RequestTarget", "scala.collection.immutable.Map")
          .put(request.target(), path);
    }
  }
}
