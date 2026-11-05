package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_OTEL_SEMANTICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.model.Uri;
import akka.http.scaladsl.server.PathMatcher;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Tracks matched Akka HTTP route templates and directive rejection results. */
@AutoService(InstrumenterModule.class)
public final class AkkaHttpPathMatcherInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public AkkaHttpPathMatcherInstrumentation() {
    // Keep this opt-in independent of an explicit parent HTTP integration enablement.
    super("akka-http-route");
  }

  @Override
  protected boolean defaultEnabled() {
    // OTel enables route capture by default; the dedicated integration switch can override it.
    // Avoid loading the global Config while setting up instrumentation.
    return super.defaultEnabled()
        && ConfigProvider.getInstance()
            .getBoolean(TRACE_OTEL_SEMANTICS_ENABLED, DEFAULT_TRACE_OTEL_SEMANTICS_ENABLED);
  }

  @Override
  public String hierarchyMarkerType() {
    return "akka.http.scaladsl.server.PathMatcher";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()))
        .or(named("akka.http.scaladsl.server.RouteResult$Rejected"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(isDeclaredBy(named("akka.http.scaladsl.server.RouteResult$Rejected"))),
        getClass().getName() + "$RejectedAdvice");
    transformer.applyAdvice(
        named("apply")
            .and(takesArguments(1))
            .and(takesArgument(0, named("akka.http.scaladsl.model.Uri$Path")))
            .and(returns(named("akka.http.scaladsl.server.PathMatcher$Matching")))
            .and(not(isBridge())),
        getClass().getName() + "$MatchAdvice");
  }

  public static final class RejectedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit() {
      AkkaHttpRouteState.onRejected();
    }
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
      AkkaHttpRouteHelper.exit(frame, matching, throwable);
    }
  }
}
