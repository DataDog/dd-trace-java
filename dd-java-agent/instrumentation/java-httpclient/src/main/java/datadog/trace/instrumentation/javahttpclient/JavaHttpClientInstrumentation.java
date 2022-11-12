package datadog.trace.instrumentation.javahttpclient;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.net.http.HttpRequest;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

@AutoService(Instrumenter.class)
public class JavaHttpClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public JavaHttpClientInstrumentation() {
    super("httpclient", "java-httpclient", "java-http-client");
  }

  @Override
  public boolean isEnabled() {
    return Platform.isJavaVersionAtLeast(11) && super.isEnabled(); }

  @Override
  public String hierarchyMarkerType() {
    return "java.net.http.HttpClient";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("send"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, named("java.net.http.HttpRequest"))),
        JavaHttpClientInstrumentation.class.getName() + "$SendAdvice");
  }

  public static class SendAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(@Advice.Argument(value = 0) HttpRequest httpRequest) {
      final AgentSpan span = startSpan("hello");
      final AgentScope scope = activateSpan(span);
      span.setOperationName("test.span");
      span.setResourceName("failing");

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      final AgentSpan span = scope.span();
      scope.close();
    }
  }
}
