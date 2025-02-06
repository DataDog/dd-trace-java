package datadog.trace.instrumentation.websocket.jsr256;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class JavaxWebsocketModule extends InstrumenterModule.Tracing {
  private final String namespace;

  public JavaxWebsocketModule() {
    this("javax", "websocket", "javax-websocket");
  }

  protected JavaxWebsocketModule(
      String namespace, String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
    this.namespace = namespace;
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> map = new HashMap<>();
    map.put("javax.websocket.Session", AgentSpan.class.getName());
    map.put("javax.websocket.RemoteEndpoint", packageName + ".HandlerContext$Sender");
    map.put("javax.websocket.MessageHandler", packageName + ".HandlerContext$Receiver");
    return map;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HandlerContext",
      packageName + ".HandlerContext$Receiver",
      packageName + ".HandlerContext$Sender",
      packageName + ".WebsocketDecorator",
      packageName + ".HandlersExtractor",
      packageName + ".HandlersExtractor$CharSequenceLenFunction",
      packageName + ".HandlersExtractor$ByteArrayLenFunction",
      packageName + ".HandlersExtractor$ByteBufferLenFunction",
      packageName + ".HandlersExtractor$SizeCalculator",
      packageName + ".ResourceNameExtractor",
      packageName + ".TracingOutputStream",
      packageName + ".TracingWriter",
      packageName + ".TracingSendHandler",
    };
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
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
