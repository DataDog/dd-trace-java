package datadog.trace.instrumentation.pulsar;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.pulsar.telemetry.PulsarRequest.*;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.core.monitor.Timer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public class ConsumerImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  private static final Logger log = LoggerFactory.getLogger(ConsumerImplInstrumentation.class);

  public ConsumerImplInstrumentation() {
    super("pulsar");
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return namedOneOf(
        "org.apache.pulsar.client.impl.ConsumerImpl",
        "org.apache.pulsar.client.impl.MultiTopicsConsumerImpl");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.pulsar.client.api.Consumer", String.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ConsumerDecorator", packageName + ".UrlParser", packageName + ".ProducerData",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    System.out.println("--- add adviceTransformations");
    String className = ConsumerImplInstrumentation.class.getName();
    transformation.applyAdvice(isConstructor(), className + "$ConsumerConstructorAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("internalReceive"))
            .and(takesArguments(2))
            .and(takesArgument(1, named("java.util.concurrent.TimeUnit"))),
        className + "$ConsumerInternalReceiveAdvice");

    // internalReceive will apply to Consumer#receive()
    transformation.applyAdvice(
        isMethod().and(isProtected()).and(named("internalReceive")).and(takesArguments(0)),
        className + "$ConsumerSyncReceiveAdvice");

    // internalReceiveAsync will apply to Consumer#receiveAsync()
    transformation.applyAdvice(
        isMethod().and(isProtected()).and(named("internalReceiveAsync")).and(takesArguments(0)),
        className + "$ConsumerAsyncReceiveAdvice");

    // internalBatchReceiveAsync will apply to Consumer#batchReceive() and
    // Consumer#batchReceiveAsync()
    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("internalBatchReceiveAsync"))
            .and(takesArguments(0)),
        className + "$ConsumerBatchAsyncReceiveAdvice");
  }

  public static class ConsumerConstructorAdvice { // 初始化
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This Consumer<?> consumer, @Advice.Argument(value = 0) PulsarClient client) {
      System.out.println("---- init ----------");
      PulsarClientImpl pulsarClient = (PulsarClientImpl) client;
      String url = pulsarClient.getLookup().getServiceUrl();
      //  VirtualFieldStore.inject(consumer, url);
      ContextStore<Consumer, String> contextStore =
          InstrumentationContext.get(Consumer.class, String.class);
      contextStore.put(consumer, url);
    }
  }

  @SuppressWarnings("unused")
  public static class ConsumerInternalReceiveAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        // @Advice.Enter Timer timer,
        @Advice.This Consumer<?> consumer,
        @Advice.Return Message<?> message,
        @Advice.Thrown Throwable throwable) {
      /*     Context parent = Context.current();
      Context current = startAndEndConsumerReceive(parent, message, timer, consumer, throwable);
      if (current != null && throwable == null) {
        // ConsumerBase#internalReceive(long,TimeUnit) will be called before
        // ConsumerListener#receive(Consumer,Message), so, need to inject Context into Message.
        VirtualFieldStore.inject(message, current);
      }*/
      System.out.println("-------- init ----Consumer Internal-------");
      ConsumerDecorator decorator = new ConsumerDecorator();
      decorator.startAndEnd(create(message), throwable);
    }
  }

  @SuppressWarnings("unused")
  public static class ConsumerSyncReceiveAdvice {

    /*    @Advice.OnMethodEnter
    public static Timer before() {
      return Timer.start();
    }*/

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter Timer timer,
        @Advice.This Consumer<?> consumer,
        @Advice.Return Message<?> message,
        @Advice.Thrown Throwable throwable) {
      /* Context parent = Context.current();
      startAndEndConsumerReceive(parent, message, timer, consumer, throwable);
      // No need to inject context to message.*/
      System.out.println("-------- init ----Consumer SyncReceive-------");
      ConsumerDecorator decorator = new ConsumerDecorator();
      decorator.startAndEnd(create(message), throwable);
    }
  }

  public static class ConsumerAsyncReceiveAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        //    @Advice.Enter Timer timer,
        @Advice.This Consumer<?> consumer,
        @Advice.Return(readOnly = false) CompletableFuture<Message<?>> future) {
      System.out.println("-------- init ----Consumer AsyncReceive-------");
      ConsumerDecorator decorator = new ConsumerDecorator();
      future = decorator.wrap(future, consumer);
      // future = wrap(future, timer, consumer);
    }
  }

  @SuppressWarnings("unused")
  public static class ConsumerBatchAsyncReceiveAdvice {
    /*

        @Advice.OnMethodEnter
        public static Timer before() {
          return Timer.start();
        }
    */

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
     //   @Advice.Enter Timer timer,
        @Advice.This Consumer<?> consumer,
        @Advice.Return(readOnly = false) CompletableFuture<Messages<?>> future) {

      System.out.println("-------- init ----Consumer batch AsyncReceive-------");
      ConsumerDecorator decorator = new ConsumerDecorator();
      future = decorator.wrapBatch(future, null, consumer);
    }
  }
}
