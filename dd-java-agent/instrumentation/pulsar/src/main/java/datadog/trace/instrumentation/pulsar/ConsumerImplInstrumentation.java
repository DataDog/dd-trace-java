package datadog.trace.instrumentation.pulsar;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.pulsar.ConsumerDecorator.startAndEnd;
import static datadog.trace.instrumentation.pulsar.ConsumerDecorator.wrap;
import static datadog.trace.instrumentation.pulsar.ConsumerDecorator.wrapBatch;
import static datadog.trace.instrumentation.pulsar.PulsarRequest.*;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public final class ConsumerImplInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes,Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(ConsumerImplInstrumentation.class);

  public ConsumerImplInstrumentation() {
    super("pulsar");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.pulsar.client.impl.ConsumerImpl",
      "org.apache.pulsar.client.impl.MultiTopicsConsumerImpl"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> store = new HashMap<>(1);
    store.put("org.apache.pulsar.client.api.Consumer", String.class.getName());
    return store;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ConsumerDecorator",
      packageName + ".UrlParser",
      packageName + ".UrlData",
      packageName + ".ProducerData",
      packageName + ".BasePulsarRequest",
      packageName + ".MessageTextMapGetter",
      packageName + ".MessageTextMapSetter",
      packageName + ".PulsarBatchRequest",
      packageName + ".PulsarRequest",
      packageName + ".MessageStore",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
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
      PulsarClientImpl pulsarClient = (PulsarClientImpl) client;
      String url = pulsarClient.getLookup().getServiceUrl();
      //  VirtualFieldStore.inject(consumer, url);
      ContextStore<Consumer, String> contextStore =
          InstrumentationContext.get(Consumer.class, String.class);
      contextStore.put(consumer, url);
    }
  }

  public static class ConsumerInternalReceiveAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This Consumer<?> consumer,
        @Advice.Return Message<?> message,
        @Advice.Thrown Throwable throwable) {
      ContextStore<Consumer, String> contextStore =
          InstrumentationContext.get(Consumer.class, String.class);
      String brokerUrl = contextStore.get(consumer);
      if (message == null) {
        return;
      }

      startAndEnd(create(message), throwable, brokerUrl);
    }
  }

  @SuppressWarnings("unused")
  public static class ConsumerSyncReceiveAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This Consumer<?> consumer,
        @Advice.Return Message<?> message,
        @Advice.Thrown Throwable throwable) {
      if (message == null) {
        return;
      }
      ContextStore<Consumer, String> contextStore =
          InstrumentationContext.get(Consumer.class, String.class);
      String brokerUrl = contextStore.get(consumer);

      startAndEnd(create(message), throwable, brokerUrl);
    }
  }

  public static class ConsumerAsyncReceiveAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This Consumer<?> consumer,
        @Advice.Return(readOnly = false) CompletableFuture<Message<?>> future) {
      ContextStore<Consumer, String> contextStore =
          InstrumentationContext.get(Consumer.class, String.class);
      String brokerUrl = contextStore.get(consumer);

      future = wrap(future, brokerUrl);
    }
  }

  @SuppressWarnings("unused")
  public static class ConsumerBatchAsyncReceiveAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This Consumer<?> consumer,
        @Advice.Return(readOnly = false) CompletableFuture<Messages<?>> future) {
      ContextStore<Consumer, String> contextStore =
          InstrumentationContext.get(Consumer.class, String.class);
      String brokerUrl = contextStore.get(consumer);
      future = wrapBatch(future, brokerUrl);
    }
  }
}
