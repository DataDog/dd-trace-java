package datadog.trace.instrumentation.akkahttp;

import static java.util.Collections.singleton;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.instrumentation.akkahttp.appsec.ScalaListCollectorMuzzleReferences;
import java.util.ArrayList;
import java.util.List;

public class AkkaHttpServerModule extends InstrumenterModule.Tracing {
  public AkkaHttpServerModule() {
    super("akka-http", "akka-http-server");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatadogWrapperHelper",
      packageName + ".DatadogAsyncHandlerWrapper",
      packageName + ".DatadogAsyncHandlerWrapper$1",
      packageName + ".DatadogAsyncHandlerWrapper$2",
      packageName + ".AkkaHttpServerHeaders",
      packageName + ".AkkaHttpServerDecorator",
      packageName + ".RecoverFromBlockedExceptionPF",
      packageName + ".UriAdapter",
      packageName + ".appsec.AkkaBlockResponseFunction",
      packageName + ".appsec.BlockingResponseHelper",
      packageName + ".appsec.ScalaListCollector",
      packageName + ".DatadogWrapperHelper",
      packageName + ".DatadogServerRequestResponseFlowWrapper",
      packageName + ".DatadogServerRequestResponseFlowWrapper$1",
      packageName + ".DatadogServerRequestResponseFlowWrapper$1$1",
      packageName + ".DatadogServerRequestResponseFlowWrapper$1$2",
      packageName + ".DatadogServerRequestResponseFlowWrapper$1$3",
      packageName + ".DatadogServerRequestResponseFlowWrapper$1$4",
    };
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return ScalaListCollectorMuzzleReferences.additionalMuzzleReferences();
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    final List<Instrumenter> ret = new ArrayList<>();
    ret.add(new AkkaHttpServerInstrumentation());
    if (InstrumenterConfig.get().isIntegrationEnabled(singleton("akka-http2"), true)) {
      ret.add(new AkkaHttp2ServerInstrumentation());
    }
    return ret;
  }
}
