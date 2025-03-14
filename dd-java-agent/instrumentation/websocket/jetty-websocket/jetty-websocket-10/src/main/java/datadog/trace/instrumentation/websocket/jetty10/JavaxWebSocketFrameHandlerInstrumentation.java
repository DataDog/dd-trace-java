package datadog.trace.instrumentation.websocket.jetty10;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import java.lang.invoke.MethodHandle;

public class JavaxWebSocketFrameHandlerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private final String namespace;

  public JavaxWebSocketFrameHandlerInstrumentation(final String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String instrumentedType() {
    return namespace + "WebSocketFrameHandler";
  }

  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArguments(9))
            .and(takesArgument(1, Object.class))
            .and(takesArgument(2, MethodHandle.class))
            .and(takesArgument(3, MethodHandle.class)),
        "datadog.trace.instrumentation.websocket.jetty10.WebSocketAdvices$OpenClose9Advice");
    transformer.applyAdvice(
        isConstructor()
            .and(takesArguments(10))
            .and(takesArgument(2, Object.class))
            .and(takesArgument(3, MethodHandle.class))
            .and(takesArgument(4, MethodHandle.class)),
        "datadog.trace.instrumentation.websocket.jetty10.WebSocketAdvices$OpenClose10Advice");
  }
}
