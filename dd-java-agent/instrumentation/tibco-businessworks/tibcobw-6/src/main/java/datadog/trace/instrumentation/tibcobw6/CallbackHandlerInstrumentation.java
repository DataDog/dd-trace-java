package datadog.trace.instrumentation.tibcobw6;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import com.tibco.bw.jms.shared.api.receive.JMSMessageCallBackHandler;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class CallbackHandlerInstrumentation extends AbstractTibcoInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "com.tibco.bw.jms.shared.api.receive.JMSMessageCallBackHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return HierarchyMatchers.implementsInterface(NameMatchers.named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(NameMatchers.named("onMessage")), getClass().getName() + "$OnMessageAdvice");
  }

  public static class OnMessageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This JMSMessageCallBackHandler handler) {
      String pinnedName =
          InstrumentationContext.get(JMSMessageCallBackHandler.class, String.class).get(handler);
      if (pinnedName != null && activeSpan() != null) {
        final AgentSpan span = activeSpan();
        AgentSpan root = span.getLocalRootSpan();
        if (root != null) {
          root.setServiceName(pinnedName);
        }
        span.setServiceName(pinnedName);
      }
    }
  }
}
