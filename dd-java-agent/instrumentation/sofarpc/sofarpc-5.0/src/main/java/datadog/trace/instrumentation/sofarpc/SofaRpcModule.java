package datadog.trace.instrumentation.sofarpc;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class SofaRpcModule extends InstrumenterModule.Tracing {

  public SofaRpcModule() {
    super("sofarpc");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SofaRpcClientDecorator",
      packageName + ".SofaRpcServerDecorator",
      packageName + ".SofaRpcInjectAdapter",
      packageName + ".SofaRpcExtractAdapter",
      packageName + ".SofaRpcProtocolContext",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new AbstractClusterInstrumentation(),
        new BoltServerProcessorInstrumentation(),
        new H2cServerTaskInstrumentation(),
        new RestServerHandlerInstrumentation(),
        new TripleServerInstrumentation(),
        new ProviderProxyInvokerInstrumentation());
  }
}
