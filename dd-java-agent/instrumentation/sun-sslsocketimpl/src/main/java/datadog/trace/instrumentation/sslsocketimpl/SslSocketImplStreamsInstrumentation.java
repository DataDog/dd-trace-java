package datadog.trace.instrumentation.sslsocketimpl;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.UsmConnection;
import datadog.trace.bootstrap.instrumentation.api.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.api.UsmMessage;
import datadog.trace.bootstrap.instrumentation.api.UsmMessageFactory;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.security.ssl.SSLSocketImpl;

import java.net.Inet6Address;

@AutoService(Instrumenter.class)
public class SslSocketImplStreamsInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes {

  private static final Logger log =
      LoggerFactory.getLogger(SslSocketImplStreamsInstrumentation.class);

  public SslSocketImplStreamsInstrumentation() {
    super("sun-sslsocketimpl-streams", "sslsocketimpl-streams", "sslsocket-streams");
  }

  @Override
  public String[] knownMatchingTypes() {
    // we instrument both input and output streams which are inner classes of the
    // SSLSocketImpl
    return new String[] {
      "sun.security.ssl.SSLSocketImpl$AppInputStream",
      "sun.security.ssl.SSLSocketImpl$AppOutputStream"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(
                named("write")
                    .and(takesArguments(3))
                    .and(takesArgument(0, byte[].class))
                    .and(takesArgument(1, int.class))
                    .and(takesArgument(2, int.class))),
        SslSocketImplStreamsInstrumentation.class.getName() + "$WriteAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(
                named("read")
                    .and(takesArguments(3))
                    .and(takesArgument(0, byte[].class))
                    .and(takesArgument(1, int.class))
                    .and(takesArgument(2, int.class))),
        SslSocketImplStreamsInstrumentation.class.getName() + "$ReadAdvice");
  }

  public static class WriteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void write(
        @Advice.FieldValue("this$0") SSLSocketImpl socket,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.Argument(1) int offset,
        @Advice.Argument(2) int len) {

      boolean isIPv6 = socket.getLocalAddress() instanceof Inet6Address;
      UsmConnection connection = new UsmConnection(socket.getLocalAddress(),socket.getLocalPort(),
                                                                  socket.getInetAddress(),socket.getPeerPort(), isIPv6);
      UsmMessage message =
          UsmMessageFactory.Supplier.getRequestMessage(connection, buffer, offset, len);
      UsmExtractor.Supplier.send(message);
    }
  }

  public static class ReadAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void read(
        @Advice.FieldValue("this$0") SSLSocketImpl socket,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.Argument(1) int offset,
        @Advice.Argument(2) int len) {

      boolean isIPv6 = socket.getLocalAddress() instanceof Inet6Address;
      UsmConnection connection = new UsmConnection(socket.getLocalAddress(),socket.getLocalPort(),socket.getInetAddress(),socket.getPeerPort(), isIPv6);
      UsmMessage message =
          UsmMessageFactory.Supplier.getRequestMessage(connection, buffer, offset, len);
      UsmExtractor.Supplier.send(message);
    }
  }
}
