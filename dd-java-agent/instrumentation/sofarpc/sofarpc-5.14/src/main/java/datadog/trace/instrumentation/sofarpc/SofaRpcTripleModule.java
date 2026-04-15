package datadog.trace.instrumentation.sofarpc;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class SofaRpcTripleModule extends InstrumenterModule.Tracing {

  public SofaRpcTripleModule() {
    super("sofarpc");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TripleGrpcMetadataExtractAdapter",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Collections.singletonList(new TripleServerInstrumentation());
  }
}
