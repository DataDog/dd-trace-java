package datadog.trace.instrumentation.rmi.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ModifierMatchers.ModifierConstraint.NON_STATIC;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ModifierMatchers.ModifierConstraint.PUBLIC;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ModifierMatchers.permitPositiveDenyNegativeModifiers;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.rmi.ThreadLocalContext.THREAD_LOCAL_CONTEXT;
import static datadog.trace.instrumentation.rmi.server.RmiServerDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class RmiServerInstrumentation extends Instrumenter.Default {

  public RmiServerInstrumentation() {
    super("rmi", "rmi-server");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".RmiServerDecorator"};
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return extendsClass(named("java.rmi.server.RemoteServer"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(permitPositiveDenyNegativeModifiers(EnumSet.of(PUBLIC, NON_STATIC))),
        getClass().getName() + "$ServerAdvice");
  }

  public static class ServerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class, inline = true)
    public static AgentScope onEnter(
        @Advice.This final Object thiz, @Advice.Origin final Method method) {
      final AgentSpan.Context context = THREAD_LOCAL_CONTEXT.getAndResetContext();

      final AgentSpan span;
      if (context == null) {
        span = startSpan("rmi.request");
      } else {
        span = startSpan("rmi.request", context);
      }

      span.setTag(DDTags.RESOURCE_NAME, DECORATE.spanNameForMethod(method))
          .setTag("span.origin.type", thiz.getClass().getCanonicalName());

      DECORATE.afterStart(span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATE.onError(scope, throwable);
      DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
    }
  }
}
