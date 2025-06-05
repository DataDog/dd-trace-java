package datadog.trace.instrumentation.jakarta3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresAnnotation;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.isAnnotatedWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jakarta3.JakartaRsAnnotationsDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.ws.rs.container.AsyncResponse;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class JakartaRsAnnotationsInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final String JAKARTA_ENDPOINT_OPERATION_NAME = "jakarta-rs.request";

  public JakartaRsAnnotationsInstrumentation() {
    super("jakarta-rs", "jakartars", "jakarta-rs-annotations");
  }

  private Collection<String> getJaxRsAnnotations() {
    final Set<String> ret = new HashSet<>();
    ret.add("jakarta.ws.rs.Path");
    ret.add("jakarta.ws.rs.DELETE");
    ret.add("jakarta.ws.rs.GET");
    ret.add("jakarta.ws.rs.HEAD");
    ret.add("jakarta.ws.rs.OPTIONS");
    ret.add("jakarta.ws.rs.POST");
    ret.add("jakarta.ws.rs.PUT");
    ret.add("jakarta.ws.rs.PATCH");
    ret.addAll(InstrumenterConfig.get().getAdditionalJaxRsAnnotations());
    return ret;
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("jakarta.ws.rs.container.AsyncResponse", AgentSpan.class.getName());
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.ws.rs.Path";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return hasSuperType(
        declaresAnnotation(named(hierarchyMarkerType()))
            .or(declaresMethod(isAnnotatedWith(named(hierarchyMarkerType())))));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.ClassHierarchyIterable",
      "datadog.trace.agent.tooling.ClassHierarchyIterable$ClassIterator",
      packageName + ".JakartaRsAnnotationsDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(hasSuperMethod(isAnnotatedWith(namedOneOf(getJaxRsAnnotations())))),
        JakartaRsAnnotationsInstrumentation.class.getName() + "$JakartaRsAnnotationsAdvice");
  }

  public static class JakartaRsAnnotationsAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope nameSpan(
        @Advice.This final Object target,
        @Advice.Origin final Method method,
        @Advice.AllArguments final Object[] args,
        @Advice.Local("asyncResponse") AsyncResponse asyncResponse) {
      ContextStore<AsyncResponse, AgentSpan> contextStore = null;
      for (final Object arg : args) {
        if (arg instanceof AsyncResponse) {
          asyncResponse = (AsyncResponse) arg;
          contextStore = InstrumentationContext.get(AsyncResponse.class, AgentSpan.class);
          if (contextStore.get(asyncResponse) != null) {
            /**
             * We are probably in a recursive call and don't want to start a new span because it
             * would replace the existing span in the asyncResponse and cause it to never finish. We
             * could work around this by using a list instead, but we likely don't want the extra
             * span anyway.
             */
            return null;
          }
          break;
        }
      }

      // Rename the parent span according to the path represented by these annotations.
      final AgentSpan parent = activeSpan();

      final AgentSpan span = startSpan(JAKARTA_ENDPOINT_OPERATION_NAME);
      span.setMeasured(true);
      DECORATE.onJakartaRsSpan(span, parent, target.getClass(), method);
      DECORATE.afterStart(span);

      final AgentScope scope = activateSpan(span);

      if (contextStore != null && asyncResponse != null) {
        contextStore.put(asyncResponse, span);
      }

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Local("asyncResponse") final AsyncResponse asyncResponse) {
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

      if (asyncResponse != null && !asyncResponse.isSuspended()) {
        // Clear span from the asyncResponse. Logically this should never happen. Added to be safe.
        InstrumentationContext.get(AsyncResponse.class, AgentSpan.class).put(asyncResponse, null);
      }
      if (asyncResponse == null || !asyncResponse.isSuspended()) {
        DECORATE.beforeFinish(span);
        span.finish();
      }
      // else span finished by AsyncResponseAdvice
      scope.close();
    }
  }
}
