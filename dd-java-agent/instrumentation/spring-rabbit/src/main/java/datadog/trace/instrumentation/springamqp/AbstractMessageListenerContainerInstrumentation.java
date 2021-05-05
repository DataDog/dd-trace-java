package datadog.trace.instrumentation.springamqp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.amqp.core.Message;

@AutoService(Instrumenter.class)
public class AbstractMessageListenerContainerInstrumentation extends Instrumenter.Tracing {

  public AbstractMessageListenerContainerInstrumentation() {
    super("spring-rabbit");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.springframework.amqp.core.Message", State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("executeListener").and(takesArgument(1, Object.class)),
        getClass().getName() + "$ActivateContinuation");
  }

  public static class ActivateContinuation {
    @Advice.OnMethodEnter
    public static TraceScope activate(@Advice.Argument(1) Object data) {
      if (data instanceof Message) {
        Message message = (Message) data;
        State state = InstrumentationContext.get(Message.class, State.class).get(message);
        if (null != state) {
          TraceScope.Continuation continuation = state.getAndResetContinuation();
          if (null != continuation) {
            return continuation.activate();
          }
        }
      }
      return null;
    }

    @Advice.OnMethodExit
    public static void close(@Advice.Enter TraceScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }
}
