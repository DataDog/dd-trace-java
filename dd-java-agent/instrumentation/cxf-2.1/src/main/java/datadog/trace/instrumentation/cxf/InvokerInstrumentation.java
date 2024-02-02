package datadog.trace.instrumentation.cxf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.cxf.message.Exchange;

@AutoService(Instrumenter.class)
public class InvokerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public InvokerInstrumentation() {
    super("cxf", "cxf-invoker");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return ClassLoaderMatchers.hasClassNamed("javax.servlet.ServletRequest")
        .or(ClassLoaderMatchers.hasClassNamed("jakarta.servlet.ServletRequest"));
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.cxf.service.invoker.Invoker";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return HierarchyMatchers.implementsInterface(NameMatchers.named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ServletHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        ElementMatchers.isMethod().and(NameMatchers.named("invoke")),
        getClass().getName() + "$PropagateSpanAdvice");
  }

  public static class PropagateSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beforeInvoke(@Advice.Argument(0) final Exchange exchange) {
      if (exchange == null || exchange.getInMessage() == null || AgentTracer.activeSpan() != null) {
        return null;
      }
      final Object span =
          ServletHelper.getServletRequestAttribute(
              exchange.getInMessage().get("HTTP.REQUEST"), HttpServerDecorator.DD_SPAN_ATTRIBUTE);
      if (span instanceof AgentSpan) {
        return AgentTracer.activateSpan((AgentSpan) span);
      }
      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void afterInvoke(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
