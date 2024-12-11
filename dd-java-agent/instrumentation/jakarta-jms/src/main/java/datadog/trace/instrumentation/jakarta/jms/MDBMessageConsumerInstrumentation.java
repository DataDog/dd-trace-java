package datadog.trace.instrumentation.jakarta.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresAnnotation;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jakarta.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jakarta.jms.JMSDecorator.JMS_CONSUME;
import static datadog.trace.instrumentation.jakarta.jms.JMSDecorator.logJMSException;
import static datadog.trace.instrumentation.jakarta.jms.MessageExtractAdapter.GETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class MDBMessageConsumerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public MDBMessageConsumerInstrumentation() {
    super("jakarta-jms", "jakarta-mdb");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JMSDecorator",
      packageName + ".MessageExtractAdapter",
      packageName + ".MessageExtractAdapter$1"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.jms.MessageListener";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        .and(
            hasSuperType(declaresAnnotation(named("jakarta.ejb.MessageDriven")))
                .or(implementsInterface(named("jakarta.ejb.MessageDrivenBean"))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("onMessage"))
            .and(takesArguments(1))
            .and(takesArgument(0, (named("jakarta.jms.Message")))),
        getClass().getName() + "$MDBAdvice");
  }

  public static class MDBAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(@Advice.Argument(0) final Message message) {
      if (CallDepthThreadLocalMap.incrementCallDepth(MessageListener.class) > 0) {
        return null;
      }
      AgentSpan.Context propagatedContext = propagate().extract(message, GETTER);
      AgentSpan span = startSpan(JMS_CONSUME, propagatedContext);
      CONSUMER_DECORATE.afterStart(span);
      CharSequence consumerResourceName;
      try {
        Destination destination = message.getJMSDestination();
        boolean isQueue = CONSUMER_DECORATE.isQueue(destination);
        String destinationName = CONSUMER_DECORATE.getDestinationName(destination);
        consumerResourceName = CONSUMER_DECORATE.toResourceName(destinationName, isQueue);
      } catch (JMSException e) {
        logJMSException(e);
        consumerResourceName = "unknown JMS destination";
      }
      CONSUMER_DECORATE.onConsume(span, message, consumerResourceName);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (null != scope) {
        CallDepthThreadLocalMap.reset(MessageListener.class);
        CONSUMER_DECORATE.onError(scope, throwable);
        scope.close();
        scope.span().finish();
      }
    }
  }
}
