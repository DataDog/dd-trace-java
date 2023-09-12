package datadog.trace.instrumentation.pulsar;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.instrumentation.pulsar.telemetry.PulsarRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.impl.ProducerImpl;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.impl.SendCallback;


import java.util.Map;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class ConsumerImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public ConsumerImplInstrumentation() {
    super("pulsar");
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return namedOneOf("org.apache.pulsar.client.impl.ConsumerImpl",
        "org.apache.pulsar.client.impl.MultiTopicsConsumerImpl");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.pulsar.client.api.Consumer", String.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    String className = ConsumerImplInstrumentation.class.getName();
    transformation.applyAdvice(isConstructor(),className+"$ConsumerConstructorAdvice");

    transformation.applyAdvice(
        isMethod().
            and(named("sendAsync1")).
            and(takesArgument(1, named("org.apache.pulsar.client.impl.SendCallback"))),
        ConsumerImplInstrumentation.class.getName() + "$ProducerSendAsyncMethodAdvice");
  }


  public static class ProducerSendAsyncMethodAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(
        @Advice.This Consumer<?> consumer, @Advice.Argument(value = 0) PulsarClient client) {

      PulsarClientImpl pulsarClient = (PulsarClientImpl) client;
      String url = pulsarClient.getLookup().getServiceUrl();
    //  VirtualFieldStore.inject(consumer, url);
      ContextStore<Consumer, String> contextStore = InstrumentationContext.get(Consumer.class, String.class);
      contextStore.put(consumer,url);
    }
  }
}
