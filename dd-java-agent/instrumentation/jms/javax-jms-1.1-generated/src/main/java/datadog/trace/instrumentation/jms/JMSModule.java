package datadog.trace.instrumentation.jms;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class JMSModule extends InstrumenterModule.Tracing {

  public JMSModule() {
    super("jms", "jms-1");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JMSDecorator",
      packageName + ".MessageInjectAdapter",
      packageName + ".MessageExtractAdapter",
      packageName + ".TracingMessageListener",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new MessageProducerInstrumentation(),
        new MessageConsumerInstrumentation(),
        new MessageListenerInstrumentation(),
        new MessageAcknowledgeInstrumentation());
  }
}
