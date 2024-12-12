package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.naming.ClassloaderServiceNames;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class JakartaServletInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public JakartaServletInstrumentation() {
    super("servlet", "servlet-5");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.http.HttpServlet";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return hasSuperType(named(hierarchyMarkerType()));
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
        getClass().getName() + "$ExtractPrincipalAdvice");
  }

  public static class ExtractPrincipalAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan before(@Advice.Argument(0) final ServletRequest request) {
      if (!(request instanceof HttpServletRequest)) {
        return null;
      }
      Object span = request.getAttribute(DD_SPAN_ATTRIBUTE);
      if (span instanceof AgentSpan
          && CallDepthThreadLocalMap.incrementCallDepth(HttpServletRequest.class) == 0) {
        final AgentSpan agentSpan = (AgentSpan) span;
        ClassloaderServiceNames.maybeSetToSpan(agentSpan);
        return agentSpan;
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
        span.setTag(DDTags.USER_NAME, httpServletRequest.getUserPrincipal().getName());
      }
    }
  }
}
