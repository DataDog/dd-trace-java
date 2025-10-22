package datadog.trace.instrumentation.ibmmq;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class IbmMqModule extends InstrumenterModule.Tracing {
  public IbmMqModule() {
    this("ibmmq");
  }

  public IbmMqModule(String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
  }

  // @Override
  // public String muzzleDirective() {
  //   return "com.ibm.mq";
  // }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.ibmmq.DestinationInstrumentation$ProducerAdvice",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(new DestinationInstrumentation());
  }
}
