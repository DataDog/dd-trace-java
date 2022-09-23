package datadog.trace.instrumentation.finatra;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static datadog.trace.instrumentation.finatra.FinatraDecorator.DECORATE;
import static datadog.trace.instrumentation.finatra.FinatraDecorator.FINATRA_CONTROLLER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.twitter.finagle.http.Request;
import com.twitter.finagle.http.Response;
import com.twitter.util.Future;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.Some;

@AutoService(Instrumenter.class)
public class FinatraInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public FinatraInstrumentation() {
    super("finatra");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".FinatraDecorator", packageName + ".Listener"};
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.twitter.finatra.http.internal.routing.Route";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith("com.twitter.finatra.").and(extendsClass(named(hierarchyMarkerType())));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("handleMatch"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("com.twitter.finagle.http.Request"))),
        FinatraInstrumentation.class.getName() + "$RouteAdvice");
  }

  public static class RouteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope nameSpan(
        @Advice.Argument(0) final Request request,
        @Advice.FieldValue("path") final String path,
        @Advice.FieldValue("clazz") final Class clazz) {

      // Update the parent "netty.request"
      final AgentSpan parent = activeSpan();
      HTTP_RESOURCE_DECORATOR.withRoute(parent, request.method().name(), path);
      parent.setTag(Tags.COMPONENT, "finatra");
      parent.setSpanName(DECORATE.spanName());

      final AgentSpan span = startSpan(FINATRA_CONTROLLER);
      DECORATE.afterStart(span);
      span.setResourceName(DECORATE.className(clazz));

      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void setupCallback(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final Some<Future<Response>> responseOption) {

      if (scope == null) {
        return;
      }

      final AgentSpan span = scope.span();
      if (throwable != null) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
        scope.close();
        return;
      }

      responseOption.get().addEventListener(new Listener(scope));
    }
  }
}
