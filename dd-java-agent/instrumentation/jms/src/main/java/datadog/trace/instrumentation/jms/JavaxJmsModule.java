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
  public JavaxJmsModule() {
    super("jms", "jms-1", "jms-2");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JMSDecorator",
      packageName + ".MessageExtractAdapter",
      packageName + ".MessageExtractAdapter$1",
      packageName + ".MessageInjectAdapter",
      packageName + ".DatadogMessageListener"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("javax.jms.MessageConsumer", MessageConsumerState.class.getName());
    contextStore.put("javax.jms.MessageProducer", MessageProducerState.class.getName());
    contextStore.put("javax.jms.Message", SessionState.class.getName());
    contextStore.put("javax.jms.Session", SessionState.class.getName());
    return contextStore;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new JMSMessageConsumerInstrumentation(),
        new JMSMessageProducerInstrumentation(),
        new MDBMessageConsumerInstrumentation(),
        new MessageInstrumentation(),
        new SessionInstrumentation());
  }
}
