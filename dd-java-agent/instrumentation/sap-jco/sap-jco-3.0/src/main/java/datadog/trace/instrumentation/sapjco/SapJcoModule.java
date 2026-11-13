package datadog.trace.instrumentation.sapjco;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

/**
 * SAP Java Connector (JCo) instrumentation. Traces outbound RFC calls as client spans and inbound
 * RFC requests as server spans, and makes SAP join the Datadog trace by sending an SAP Extended
 * Passport built from the active span (see {@link DatadogPassport}).
 */
@AutoService(InstrumenterModule.class)
public class SapJcoModule extends InstrumenterModule.Tracing {

  public SapJcoModule() {
    super("sap-jco");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new JCoClientInstrumentation(), new JCoServerInstrumentation());
  }
}
