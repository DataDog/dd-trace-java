package datadog.trace.instrumentation.java.net;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.concreteClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.sslsocket.UsmFilterInputStream;
import datadog.trace.bootstrap.instrumentation.sslsocket.UsmFilterOutputStream;
import datadog.trace.bootstrap.instrumentation.usm.UsmConnection;
import datadog.trace.bootstrap.instrumentation.usm.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.usm.UsmMessage;
import datadog.trace.bootstrap.instrumentation.usm.UsmMessageFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import javax.net.ssl.SSLSocket;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class SslSocketInstrumentation extends InstrumenterModule.Usm
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice {

  public SslSocketInstrumentation() {
    super("sslsocket");
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("javax.net.ssl.SSLSocket")).and(concreteClass());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("close").and(takesArguments(0))),
        SslSocketInstrumentation.class.getName() + "$CloseAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getInputStream")),
        SslSocketInstrumentation.class.getName() + "$GetInputStreamAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getOutputStream")),
        SslSocketInstrumentation.class.getName() + "$GetOutputStreamAdvice");
  }

  public static final class CloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void close(@Advice.This final SSLSocket socket) {
      boolean isIPv6 = socket.getLocalAddress() instanceof Inet6Address;
      UsmConnection connection =
          new UsmConnection(
              socket.getLocalAddress(),
              socket.getLocalPort(),
              socket.getInetAddress(),
              socket.getPort(),
              isIPv6);
      UsmMessage message = UsmMessageFactory.Supplier.getCloseMessage(connection);
      UsmExtractor.Supplier.send(message);
    }
  }

  public static final class GetOutputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getOutputStream(
        @Advice.This final SSLSocket socket,
        @Advice.Return(readOnly = false) OutputStream retValue) {
      retValue = new UsmFilterOutputStream(retValue, socket);
    }
  }

  public static final class GetInputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getInputStream(
        @Advice.This final SSLSocket socket,
        @Advice.Return(readOnly = false) InputStream retValue) {
      retValue = new UsmFilterInputStream(retValue, socket);
    }
  }
}
