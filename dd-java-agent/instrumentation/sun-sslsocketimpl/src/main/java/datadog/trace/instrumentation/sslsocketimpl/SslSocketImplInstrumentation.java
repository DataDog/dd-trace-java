package datadog.trace.instrumentation.sslsocketimpl;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.UsmConnection;
import datadog.trace.bootstrap.instrumentation.api.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.api.UsmMessage;
import datadog.trace.bootstrap.instrumentation.api.UsmMessageFactory;
import net.bytebuddy.asm.Advice;
import sun.security.ssl.SSLSocketImpl;

import java.net.Inet6Address;

@AutoService(Instrumenter.class)
public class SslSocketImplInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public SslSocketImplInstrumentation() {
    super("sun-sslsocketimpl", "sslsocketimpl", "sslsocket");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("close").and(takesArguments(0))),
        SslSocketImplInstrumentation.class.getName() + "$CloseAdvice");
  }

  @Override
  public String instrumentedType() {
    return "sun.security.ssl.SSLSocketImpl";
  }

  public static class CloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void close(@Advice.This SSLSocketImpl socket) {
      boolean isIPv6= socket.getLocalAddress() instanceof Inet6Address;
      UsmConnection connection = new UsmConnection(socket.getLocalAddress(),socket.getLocalPort(),socket.getInetAddress(),socket.getPeerPort(), isIPv6);
      UsmMessage message = UsmMessageFactory.Supplier.getCloseMessage(connection);
      UsmExtractor.Supplier.send(message);
    }
  }
}
