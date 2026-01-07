package datadog.trace.instrumentation.cics;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.cics.CicsDecorator.DECORATE;
import static datadog.trace.instrumentation.cics.CicsDecorator.GATEWAY_FLOW_OPERATION;

import com.ibm.connector2.cics.ECIInteraction;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public final class JavaGatewayInterfaceInstrumentation
    implements Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice,
        Instrumenter.WithTypeStructure {
  @Override
  public String hierarchyMarkerType() {
    return "com.ibm.ctg.client.JavaGatewayInterface";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    // Only instrument subclasses that have a socket field (TcpJavaGateway, SslJavaGateway)
    return declaresField(named("socJGate"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("flow"), getClass().getName() + "$FlowAdvice");
  }

  public static class FlowAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.FieldValue("strAddress") final String strAddress,
        @Advice.FieldValue("iPort") final int port,
        @Advice.FieldValue("ipGateway") final InetAddress ipGateway) {
      // Coordinating with ECIInteractionInstrumentation
      final int callDepth = CallDepthThreadLocalMap.getCallDepth(ECIInteraction.class);
      if (callDepth > 0) {
        // Inside execute() - add connection tags to the existing span instead of creating new one
        final AgentSpan parentSpan = activeSpan();
        if (parentSpan != null) {
          DECORATE.onConnection(parentSpan, strAddress, port, ipGateway);
        }
        return null;
      }

      // Not inside execute() - create a new span
      final AgentSpan span = startSpan(GATEWAY_FLOW_OPERATION);
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, strAddress, port, ipGateway);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.FieldValue("socJGate") final Socket socket) {
      if (null == scope) {
        return;
      }

      final AgentSpan span = scope.span();

      if (socket != null) {
        final SocketAddress socketAddress = socket.getLocalSocketAddress();
        if (socketAddress instanceof InetSocketAddress) {
          DECORATE.onLocalConnection(span, (InetSocketAddress) socketAddress);
        }
      }

      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      span.finish();
      scope.close();
    }
  }
}
