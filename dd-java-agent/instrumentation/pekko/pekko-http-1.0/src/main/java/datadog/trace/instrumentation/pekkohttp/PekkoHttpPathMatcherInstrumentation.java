package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.pekko.http.scaladsl.model.Uri;
import org.apache.pekko.http.scaladsl.server.PathMatcher;

/** Captures route templates from successful Pekko HTTP path matchers. */
@AutoService(InstrumenterModule.class)
public final class PekkoHttpPathMatcherInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public PekkoHttpPathMatcherInstrumentation() {
    super("pekko-http", "pekko-http-server");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().isTraceOtelSemanticsEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.pekko.http.scaladsl.server.PathMatcher";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("apply")
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.apache.pekko.http.scaladsl.model.Uri$Path")))
            .and(returns(named("org.apache.pekko.http.scaladsl.server.PathMatcher$Matching")))
            .and(not(isBridge())),
        getClass().getName() + "$MatchAdvice");
  }

  public static final class MatchAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static PekkoHttpRouteHelper.Frame enter(@Advice.Argument(0) Uri.Path path) {
      return PekkoHttpRouteHelper.enter(path);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter PekkoHttpRouteHelper.Frame frame,
        @Advice.Return PathMatcher.Matching<?> matching,
        @Advice.Thrown Throwable throwable) {
      PekkoHttpRouteHelper.exit(frame, matching, throwable);
    }
  }
}
