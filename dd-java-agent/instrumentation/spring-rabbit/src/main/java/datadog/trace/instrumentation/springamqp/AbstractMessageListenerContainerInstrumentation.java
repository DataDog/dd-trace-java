package datadog.trace.instrumentation.springamqp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.instrumentation.springamqp.RabbitListenerDecorator.AMQP_CONSUME;
import static datadog.trace.instrumentation.springamqp.RabbitListenerDecorator.DECORATE;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collection;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.amqp.core.Message;

@AutoService(InstrumenterModule.class)
public class AbstractMessageListenerContainerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, ExcludeFilterProvider {

  public AbstractMessageListenerContainerInstrumentation() {
    super("spring-rabbit");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".RabbitListenerDecorator"};
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.springframework.amqp.core.Message", State.class.getName());
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    // Even though this class isn't immediately relevant to spring-rabbit, it's loaded by the test.
    return singletonMap(
        RUNNABLE,
        singleton("org.springframework.boot.logging.logback.LogbackLoggingSystem$ShutdownHandler"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("executeListener").and(takesArgument(1, Object.class)),
        getClass().getName() + "$ActivateContinuation");
  }

  public static class ActivateContinuation {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope activate(@Advice.Argument(1) Object data) {
      if (data instanceof Message) {
        Message message = (Message) data;
        State state = InstrumentationContext.get(Message.class, State.class).get(message);
        if (null != state) {
          AgentScope.Continuation continuation = state.getAndResetContinuation();
          if (null != continuation) {
            try (AgentScope scope = continuation.activate()) {
              AgentSpan span = startSpan(AMQP_CONSUME);
              span.setMeasured(true);
              DECORATE.afterStart(span);
              DECORATE.onConsume(span, message.getMessageProperties().getConsumerQueue());
              return activateSpan(span);
            }
          }
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void close(@Advice.Enter AgentScope scope, @Advice.Thrown Throwable error) {
      if (null != scope) {
        AgentSpan span = scope.span();
        if (null != error) {
          DECORATE.onError(span, error);
        }
        DECORATE.beforeFinish(span);
        scope.close();
        span.finish();
      }
    }
  }
}
