package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.model.Uri;
import akka.http.scaladsl.server.PathMatcher;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Captures route templates from successful Akka HTTP path matchers. */
@AutoService(InstrumenterModule.class)
public final class AkkaHttpPathMatcherInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public AkkaHttpPathMatcherInstrumentation() {
    super("akka-http", "akka-http-server");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().isTraceOtelSemanticsEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "akka.http.scaladsl.server.PathMatcher";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan",
        packageName + ".AkkaHttpRouteHelper$RouteState");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AkkaHttpRouteHelper",
      packageName + ".AkkaHttpRouteHelper$Frame",
      packageName + ".AkkaHttpRouteHelper$Part",
      packageName + ".AkkaHttpRouteHelper$RouteState"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("apply")
            .and(takesArguments(1))
            .and(takesArgument(0, named("akka.http.scaladsl.model.Uri$Path")))
            .and(returns(named("akka.http.scaladsl.server.PathMatcher$Matching")))
            .and(not(isBridge())),
        getClass().getName() + "$MatchAdvice");
  }

  public static final class MatchAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AkkaHttpRouteHelper.Frame enter(@Advice.Argument(0) Uri.Path path) {
      return AkkaHttpRouteHelper.enter(path);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter AkkaHttpRouteHelper.Frame frame,
        @Advice.Return PathMatcher.Matching<?> matching,
        @Advice.Thrown Throwable throwable) {
      AkkaHttpRouteHelper.exit(
          frame,
          matching,
          throwable,
          InstrumentationContext.get(AgentSpan.class, AkkaHttpRouteHelper.RouteState.class));
    }
  }
}
