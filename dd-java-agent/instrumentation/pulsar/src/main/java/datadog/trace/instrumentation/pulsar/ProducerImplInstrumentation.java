package datadog.trace.instrumentation.pulsar;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.impl.ProducerImpl;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.impl.SendCallback;

@AutoService(InstrumenterModule.class)
public final class ProducerImplInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy , Instrumenter.HasMethodAdvice{

  public static final String CLASS_NAME = "org.apache.pulsar.client.impl.ProducerImpl";

  public ProducerImplInstrumentation() {
    super("pulsar");
  }

  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(hierarchyMarkerType());
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> store = new HashMap<>(1);
    store.put("org.apache.pulsar.client.impl.ProducerImpl", packageName+".ProducerData");
    return store;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ProducerDecorator",
      packageName + ".ProducerData",
      packageName + ".UrlParser",
      packageName + ".SendCallbackWrapper",
      packageName + ".UrlData",
      packageName + ".BasePulsarRequest",
      packageName + ".MessageTextMapGetter",
      packageName + ".MessageTextMapSetter",
      packageName + ".PulsarBatchRequest",
      packageName + ".PulsarRequest",

    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isConstructor()
            .and(isPublic())
            .and(
                takesArgument(0, hasSuperType(named("org.apache.pulsar.client.api.PulsarClient")))),
        ProducerImplInstrumentation.class.getName() + "$ProducerImplConstructorAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("sendAsync"))
            .and(takesArgument(1, named("org.apache.pulsar.client.impl.SendCallback"))),
        ProducerImplInstrumentation.class.getName() + "$ProducerSendAsyncMethodAdvice");
  }

  public static class ProducerImplConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This ProducerImpl<?> producer, @Advice.Argument(0) PulsarClient client) {
      PulsarClientImpl pulsarClient = (PulsarClientImpl) client;
      String brokerUrl = pulsarClient.getLookup().getServiceUrl();
      String topic = producer.getTopic();
      //  VirtualFieldStore.inject(producer, brokerUrl, topic); todo 存储
      ContextStore<ProducerImpl, ProducerData> contextStore =
          InstrumentationContext.get(ProducerImpl.class, ProducerData.class);
      contextStore.put(producer, ProducerData.create(brokerUrl, topic));
    }
  }

  //  ------------------------------------- 发送消息 --------------------------
  public static class ProducerSendAsyncMethodAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This ProducerImpl<?> producer,
        @Advice.Argument(0) Message<?> message,
        @Advice.Argument(value = 1, readOnly = false) SendCallback callback) {

      ContextStore<ProducerImpl, ProducerData> contextStore =
          InstrumentationContext.get(ProducerImpl.class, ProducerData.class);
      ProducerData producerData = contextStore.get(producer);
      
      PulsarRequest request =
          PulsarRequest.create(message, ProducerData.create(producerData.url, producerData.topic));

      AgentScope scope = ProducerDecorator.start(request);

      callback = new SendCallbackWrapper(scope, request, callback);
    }
  }
}
