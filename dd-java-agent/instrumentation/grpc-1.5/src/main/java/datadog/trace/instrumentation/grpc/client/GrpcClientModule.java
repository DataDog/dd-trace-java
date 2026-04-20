package datadog.trace.instrumentation.grpc.client;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class GrpcClientModule extends InstrumenterModule.Tracing {

  public GrpcClientModule() {
    super("grpc", "grpc-client", "grpc-message");
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
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("io.grpc.ClientCall", AgentSpan.class.getName());
    contextStores.put("io.grpc.internal.ClientStreamListener", AgentSpan.class.getName());
    contextStores.put(Runnable.class.getName(), State.class.getName());
    return contextStores;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new ClientCallImplInstrumentation(),
        new AbstractClientStreamInstrumentation(),
        new ClientStreamListenerImplInstrumentation(),
        new MessagesAvailableInstrumentation());
  }
}
