package datadog.trace.instrumentation.servlet.http;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.servlet.http.HttpServletResponseDecorator.DECORATE;
import static datadog.trace.instrumentation.servlet.http.HttpServletResponseDecorator.SERVLET_RESPONSE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class HttpServletResponseInstrumentation extends Instrumenter.Default {
  public HttpServletResponseInstrumentation() {
    super("servlet", "servlet-response");
  }

  public static final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassesNamed("javax.servlet.http.HttpServletResponse");

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return CLASS_LOADER_MATCHER;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("javax.servlet.http.HttpServletResponse"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.servlet.ServletRequestSetter",
      packageName + ".HttpServletResponseDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(namedOneOf("sendError", "sendRedirect"), SendAdvice.class.getName());
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("javax.servlet.http.HttpServletResponse", Boolean.class.getName());
  }

  public static class SendAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope start(
        @Advice.Origin("#m") final String method, @Advice.This final HttpServletResponse resp) {
      if (activeSpan() == null) {
        // Don't want to generate a new top-level span
        return null;
      }
      ContextStore<HttpServletResponse, Boolean> contextStore =
          InstrumentationContext.get(HttpServletResponse.class, Boolean.class);
      if (contextStore.get(resp) == null) {
        // Missing the response->request linking... probably in a wrapped instance.
        return null;
      }
      // remove it now it's served its purpose in case it's hanging around in a weak map
      contextStore.put(resp, null);

      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpServletResponse.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(SERVLET_RESPONSE);
      DECORATE.afterStart(span);

      span.setTag(
          DDTags.RESOURCE_NAME, DECORATE.spanNameForMethod(HttpServletResponse.class, method));

      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      CallDepthThreadLocalMap.reset(HttpServletResponse.class);

      DECORATE.onError(scope, throwable);
      DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
    }
  }
}
