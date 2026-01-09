package datadog.trace.instrumentation.grpc.server;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class GrpcServerModule extends InstrumenterModule.Tracing {
  public GrpcServerModule() {
    super("grpc", "grpc-server");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GrpcServerDecorator",
      packageName + ".GrpcServerDecorator$1",
      packageName + ".GrpcExtractAdapter",
      packageName + ".TracingServerInterceptor",
      packageName + ".TracingServerInterceptor$TracingServerCall",
      packageName + ".TracingServerInterceptor$TracingServerCallListener",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.grpc.ServerBuilder", Boolean.class.getName());
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    final List<Instrumenter> ret = new ArrayList<>(2);
    ret.add(new GrpcServerBuilderInstrumentation());
    if (!JavaVirtualMachine.isGraalVM()
        && InstrumenterConfig.get()
            .isIntegrationEnabled(singleton("grpc-server-code-origin"), true)) {
      ret.add(new MethodHandlersInstrumentation());
    }
    return ret;
  }
}
