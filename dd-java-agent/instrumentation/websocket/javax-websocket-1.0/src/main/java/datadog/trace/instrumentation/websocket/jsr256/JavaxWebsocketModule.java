package datadog.trace.instrumentation.websocket.jsr256;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class JavaxWebsocketModule extends InstrumenterModule.Tracing {
  private final String namespace;

  public JavaxWebsocketModule() {
    this("javax", "javax-websocket", "websocket");
  }

  protected JavaxWebsocketModule(
      String namespace, String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
    this.namespace = namespace;
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> map = new HashMap<>();
    map.put(namespace + ".websocket.Session", HandlerContext.Sender.class.getName());
    map.put(namespace + ".websocket.RemoteEndpoint", HandlerContext.Sender.class.getName());
    map.put(namespace + ".websocket.MessageHandler", HandlerContext.Receiver.class.getName());
    return map;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TracingOutputStream",
      packageName + ".TracingWriter",
      packageName + ".TracingSendHandler",
    };
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isWebsocketTracingEnabled();
  }

  @Override
  public String muzzleDirective() {
    return "javax-websocket";
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new EndpointInstrumentation(namespace),
        new SessionInstrumentation(namespace),
        new MessageHandlerInstrumentation(namespace),
        new BasicRemoteEndpointInstrumentation(namespace),
        new AsyncRemoteEndpointInstrumentation(namespace));
  }
}
