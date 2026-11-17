package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.KnownTags.USER_PRINCIPAL;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class JakartaServletInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public JakartaServletInstrumentation() {
    super("servlet", "servlet-5");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.http.HttpServlet";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return hasSuperType(named(hierarchyMarkerType()))
        .or(implementsInterface(named("jakarta.servlet.FilterChain")));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("service"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, named("jakarta.servlet.ServletRequest")))
            .and(takesArgument(1, named("jakarta.servlet.ServletResponse"))),
        getClass().getName() + "$JakartaServletAdvice");
  }

  public static class JakartaServletAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan before(@Advice.Argument(0) ServletRequest request) {
      if (!(request instanceof HttpServletRequest)) {
        return null;
      }

      Object contextAttr = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
      if (contextAttr instanceof Context
          && CallDepthThreadLocalMap.incrementCallDepth(HttpServletRequest.class) == 0) {
        final Context context = (Context) contextAttr;
        final AgentSpan span = spanFromContext(context);
        if (span != null) {
          ClassloaderConfigurationOverrides.maybeEnrichSpan(span);
          return span;
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(
        @Advice.Enter final AgentSpan span, @Advice.Argument(0) final ServletRequest request) {
      if (span == null) {
        return;
      }
      CallDepthThreadLocalMap.reset(HttpServletRequest.class);
      final HttpServletRequest httpServletRequest =
          (HttpServletRequest) request; // at this point the cast should be safe
      if (Config.get().isServletPrincipalEnabled()
          && httpServletRequest.getUserPrincipal() != null) {
        span.setTag(USER_PRINCIPAL, httpServletRequest.getUserPrincipal().getName());
      }
    }
  }
}
