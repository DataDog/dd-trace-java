package datadog.trace.instrumentation.jms;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.jms.MessageConsumerState;
import datadog.trace.bootstrap.instrumentation.jms.MessageProducerState;
import datadog.trace.bootstrap.instrumentation.jms.SessionState;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class JavaxJmsModule extends InstrumenterModule.Tracing {
  private final String namespace;

  public JavaxJmsModule() {
    this("javax", "jms", "jms-1", "jms-2");
  }

  public JavaxJmsModule(String namespace, String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
    this.namespace = namespace;
  }

  @Override
  public String muzzleDirective() {
    return "javax.jms";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JMSDecorator",
      packageName + ".MessageExtractAdapter",
      packageName + ".MessageExtractAdapter$1",
      packageName + ".MessageInjectAdapter",
      packageName + ".DatadogMessageListener",
      packageName + ".JMSMessageConsumerInstrumentation$JMSLogger"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put(namespace + ".jms.MessageConsumer", MessageConsumerState.class.getName());
    contextStore.put(namespace + ".jms.MessageProducer", MessageProducerState.class.getName());
    contextStore.put(namespace + ".jms.Message", SessionState.class.getName());
    contextStore.put(namespace + ".jms.Session", SessionState.class.getName());
    return contextStore;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new JMSMessageConsumerInstrumentation(namespace),
        new JMSMessageProducerInstrumentation(namespace),
        new MDBMessageConsumerInstrumentation(namespace),
        new MessageInstrumentation(namespace),
        new SessionInstrumentation(namespace));
  }
}
