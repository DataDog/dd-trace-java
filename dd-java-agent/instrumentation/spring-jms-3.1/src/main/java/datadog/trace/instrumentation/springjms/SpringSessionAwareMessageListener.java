package datadog.trace.instrumentation.springjms;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_ONMESSAGE;
import static datadog.trace.instrumentation.jms.MessageExtractAdapter.GETTER;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import javax.jms.Message;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class SpringSessionAwareMessageListener extends Instrumenter.Tracing {

  public SpringSessionAwareMessageListener() {
    super("jms-spring", "jms", "jms-1", "jms-2");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.springframework.jms.listener.SessionAwareMessageListener");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(
            named("org.springframework.jms.listener.SessionAwareMessageListener"))
        .and(
            not(named("org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.jms.JMSDecorator",
      "datadog.trace.instrumentation.jms.MessageExtractAdapter",
      "datadog.trace.instrumentation.jms.MessageExtractAdapter$1",
      "datadog.trace.instrumentation.jms.MessageInjectAdapter"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("onMessage")
            .and(takesArguments(2))
            .and(takesArgument(0, named("javax.jms.Message")))
            .and(takesArgument(1, named("javax.jms.Session")).and(isPublic())),
        SpringSessionAwareMessageListener.class.getName() + "$MessageListenerAdvice");
  }

  public static class MessageListenerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final Message message, @Advice.This final Object listener) {

      final Context extractedContext = propagate().extract(message, GETTER);
      final AgentSpan span = startSpan(JMS_ONMESSAGE, extractedContext);
      CONSUMER_DECORATE.afterStart(span);
      CONSUMER_DECORATE.onReceive(span, message);

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
      CONSUMER_DECORATE.onError(scope, throwable);
      CONSUMER_DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
    }
  }
}
