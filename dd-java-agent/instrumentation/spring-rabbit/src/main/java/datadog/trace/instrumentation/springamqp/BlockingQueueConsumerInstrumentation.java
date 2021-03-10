package datadog.trace.instrumentation.springamqp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.TreeMap;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.support.Delivery;

@AutoService(Instrumenter.class)
public class BlockingQueueConsumerInstrumentation extends Instrumenter.Tracing {
  public BlockingQueueConsumerInstrumentation() {
    super("spring-rabbit");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.springframework.amqp.rabbit.listener.BlockingQueueConsumer");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("handle")
            .and(takesArgument(0, named("org.springframework.amqp.rabbit.support.Delivery"))),
        getClass().getName() + "$TransferState");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new TreeMap<>();
    contextStore.put("org.springframework.amqp.core.Message", State.class.getName());
    contextStore.put("org.springframework.amqp.rabbit.support.Delivery", State.class.getName());
    return contextStore;
  }

  public static class TransferState {
    @Advice.OnMethodExit
    public static void transfer(
        @Advice.Argument(0) Delivery delivery, @Advice.Return Message message) {
      if (null != delivery) {
        ContextStore<Delivery, State> from =
            InstrumentationContext.get(Delivery.class, State.class);
        State state = from.get(delivery);
        if (null != state) {
          from.put(delivery, null);
          InstrumentationContext.get(Message.class, State.class).put(message, state);
        }
      }
    }
  }
}
