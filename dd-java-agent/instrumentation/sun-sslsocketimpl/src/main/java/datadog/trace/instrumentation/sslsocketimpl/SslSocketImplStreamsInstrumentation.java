package datadog.trace.instrumentation.sslsocketimpl;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.bootstrap.instrumentation.api.UsmConnection;
import datadog.trace.bootstrap.instrumentation.api.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.api.UsmMessage;
import datadog.trace.bootstrap.instrumentation.api.UsmMessageFactory;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.net.ssl.SSLSocket;

@AutoService(Instrumenter.class)
public class SslSocketImplStreamsInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  private static final Logger log =
      LoggerFactory.getLogger(SslSocketImplStreamsInstrumentation.class);

  public SslSocketImplStreamsInstrumentation() {
    super("sun-sslsocketimpl-streams", "sslsocketimpl-streams", "sslsocket-streams");
  }

  @Override
  public String hierarchyMarkerType() {
    // we instrument both input and output streams which are inner classes of the
    // SSLSocketImpl
    return "javax.net.ssl.SSLSocket";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return HierarchyMatchers.extendsClass(named("javax.net.ssl.SSLSocket"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
        transformation.applyAdvice(isMethod().and(named("getInputStream")),
            SslSocketImplStreamsInstrumentation.class.getName() + "$GetInputStreamAdvice");
    transformation.applyAdvice(isMethod().and(named("getOutputStream")),
        SslSocketImplStreamsInstrumentation.class.getName() + "$GetOutputStreamAdvice");
  }

  public static class GetOutputStreamAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getOutputStream(
        @Advice.This final SSLSocket socket,
        @Advice.Return(readOnly = false) OutputStream retValue) {
        retValue = new FilterOutputStream(retValue) {
          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            boolean isIPv6 = socket.getLocalAddress() instanceof Inet6Address;
            UsmConnection connection =
                new UsmConnection(
                    socket.getLocalAddress(),
                    socket.getLocalPort(),
                    socket.getInetAddress(),
                    socket.getPort(),
                    isIPv6);
            UsmMessage message =
                UsmMessageFactory.Supplier.getRequestMessage(connection, b, off, len);
            UsmExtractor.Supplier.send(message);
            super.write(b, off, len);
          }
        };
    }
  }

  public static class GetInputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getInputStream(
        @Advice.This final SSLSocket socket,
        @Advice.Return(readOnly = false) InputStream retValue) {
        retValue = new FilterInputStream(retValue) {
          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            boolean isIPv6 = socket.getLocalAddress() instanceof Inet6Address;
            UsmConnection connection =
                new UsmConnection(
                    socket.getLocalAddress(),
                    socket.getLocalPort(),
                    socket.getInetAddress(),
                    socket.getPort(),
                    isIPv6);
            UsmMessage message =
                UsmMessageFactory.Supplier.getRequestMessage(connection, b, off, len);
            UsmExtractor.Supplier.send(message);
            return super.read(b, off, len);
          }
        };
    }
  }
}
