package datadog.trace.instrumentation.armeria.grpc.client;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class ArmeriaGrpcClientModule extends InstrumenterModule.Tracing {
  public ArmeriaGrpcClientModule() {
    super("armeria-grpc-client", "armeria-grpc", "armeria", "grpc-client", "grpc");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>();
    contextStore.put("io.grpc.ClientCall", AgentSpan.class.getName());
    contextStore.put(
        "com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer", "io.grpc.ClientCall");
    return contextStore;
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference(
          new String[0],
          1,
          "com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer",
          null,
          new String[0],
          new Reference.Field[0],
          new Reference.Method[0])
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GrpcClientDecorator",
      packageName + ".GrpcClientDecorator$1",
      packageName + ".GrpcInjectAdapter"
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new ArmeriaMessageDeframerInstrumentation(),
        new ArmeriaMessageDeframerInstrumentation(),
        new ClientCallImplInstrumentation());
  }
}
