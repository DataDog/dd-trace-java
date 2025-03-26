package datadog.trace.instrumentation.websocket.jetty10;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;

public class JavaxWebSocketFrameHandlerFactoryInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private final String namespace;

  public JavaxWebSocketFrameHandlerFactoryInstrumentation(final String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String instrumentedType() {
    return namespace + "WebSocketFrameHandlerFactory";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("createMessageSink")
            .and(takesArgument(0, named(namespace + "WebSocketSession")))
            .and(takesArgument(1, named(namespace + "WebSocketMessageMetadata"))),
        "datadog.trace.instrumentation.websocket.jetty10.WebSocketAdvices$MessageSinkAdvice");
  }
}
