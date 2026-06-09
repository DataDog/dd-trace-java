package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.ACKNOWLEDGE_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.JAVA_JMS;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_ACKNOWLEDGE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.jms.Destination;
import javax.jms.Message;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class MessageAcknowledgeInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "javax.jms.Message";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("javax.jms.Message"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("acknowledge")).and(takesNoArguments()),
        MessageAcknowledgeInstrumentation.class.getName() + "$AcknowledgeAdvice");
  }

  public static class AcknowledgeAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This Message message) {
      AgentSpan span = startSpan(JAVA_JMS.toString(), JMS_ACKNOWLEDGE);
      ACKNOWLEDGE_DECORATE.afterStart(span);

      Destination destination = null;
      try {
        destination = message.getJMSDestination();
      } catch (Exception e) {
        // ignore
      }
      ACKNOWLEDGE_DECORATE.onAcknowledge(span, destination);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter AgentScope scope, @Advice.Thrown Throwable throwable) {
      if (scope == null) {
        return;
      }
      AgentSpan span = scope.span();
      if (throwable != null) {
        ACKNOWLEDGE_DECORATE.onError(span, throwable);
      }
      ACKNOWLEDGE_DECORATE.beforeFinish(span);
      span.finish();
      scope.close();
    }
  }
}
