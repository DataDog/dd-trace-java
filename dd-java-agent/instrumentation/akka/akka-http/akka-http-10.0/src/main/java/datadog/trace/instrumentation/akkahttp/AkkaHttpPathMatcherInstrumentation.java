package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.model.Uri;
import akka.http.scaladsl.server.PathMatcher;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Records literal metadata and matched route components. */
public final class AkkaHttpPathMatcherInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  @Override
  public String hierarchyMarkerType() {
    return "akka.http.scaladsl.server.PathMatcher";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()))
        .or(named("akka.http.scaladsl.server.PathMatcher$"));
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
    transformer.applyAdvice(
        named("apply")
            .and(takesArgument(0, named("akka.http.scaladsl.model.Uri$Path")))
            .and(returns(named("akka.http.scaladsl.server.PathMatcher")))
            .and(isDeclaredBy(named("akka.http.scaladsl.server.PathMatcher$"))),
        getClass().getName() + "$LiteralAdvice");
  }

  public static final class MatchAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.This PathMatcher<?> matcher,
        @Advice.Argument(0) Uri.Path path,
        @Advice.Return PathMatcher.Matching<?> matching) {
      AkkaHttpRouteHelper.matched(
          matcher, path, matching, InstrumentationContext.get(PathMatcher.class, String.class));
    }
  }

  public static final class LiteralAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.Argument(0) Uri.Path path, @Advice.Return PathMatcher<?> matcher) {
      InstrumentationContext.get(PathMatcher.class, String.class).put(matcher, path.toString());
    }
  }
}
