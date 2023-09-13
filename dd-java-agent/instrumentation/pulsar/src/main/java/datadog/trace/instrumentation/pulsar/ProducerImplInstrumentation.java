package datadog.trace.instrumentation.pulsar;

import static datadog.trace.instrumentation.pulsar.ProducerData.create;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.pulsar.telemetry.PulsarRequest;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.impl.ProducerImpl;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.impl.SendCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public class ProducerImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy{

  private static final Logger log = LoggerFactory.getLogger(ProducerImplInstrumentation.class);

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
    return singletonMap("org.apache.pulsar.client.impl.ProducerImpl", ProducerData.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
        packageName + ".ProducerDecorator",
        packageName + ".SendCallbackWrapper",
        packageName + ".ProducerData",
        packageName + ".UrlParser",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
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

  public static class ProducerImplConstructorAdvice{
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void intercept(
        @Advice.This ProducerImpl<?> producer, @Advice.Argument(value = 0) PulsarClient client) {
      System.out.println("--- Producer ImplConstructorAdvice ");
      PulsarClientImpl pulsarClient = (PulsarClientImpl) client;
      String brokerUrl = pulsarClient.getLookup().getServiceUrl();
      String topic = producer.getTopic();
    //  VirtualFieldStore.inject(producer, brokerUrl, topic); todo 存储
      ContextStore<ProducerImpl, ProducerData> contextStore = InstrumentationContext.get(ProducerImpl.class, ProducerData.class);
      contextStore.put(producer,create(brokerUrl,topic));
    }
  }

  //  ------------------------------------- 发送消息 --------------------------
  public static class ProducerSendAsyncMethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(
        @Advice.This ProducerImpl<?> producer,
        @Advice.Argument(value = 0) Message<?> message,
        @Advice.Argument(value = 1, readOnly = false) SendCallback callback) {
/*      Context parent = Context.current();
      PulsarRequest request = PulsarRequest.create(message, VirtualFieldStore.extract(producer));

      if (!producerInstrumenter().shouldStart(parent, request)) {
        return;
      }

      Context context = producerInstrumenter().start(parent, request);
      callback = new SendCallbackWrapper(context, request, callback);
      */
      System.out.println("-------- init  Producer Send AsyncMethodAdvice-------");
      ContextStore<ProducerImpl, ProducerData> contextStore =InstrumentationContext.get(ProducerImpl.class, ProducerData.class);
      ProducerData producerData = contextStore.get(producer);
      if (producerData==null){
        log.error("producerData is null");
        return;
      }
      PulsarRequest request = PulsarRequest.create(message,ProducerData.create(producerData.url,producerData.topic));
      AgentScope scope = new ProducerDecorator().start(request);
      callback = new SendCallbackWrapper(scope.span(), request, callback);
    }
  }

}
