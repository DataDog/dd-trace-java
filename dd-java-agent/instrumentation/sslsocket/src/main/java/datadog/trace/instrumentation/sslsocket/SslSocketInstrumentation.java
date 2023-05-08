package datadog.trace.instrumentation.sslsocket;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.concreteClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.usm.Connection;
import datadog.trace.bootstrap.instrumentation.usm.Extractor;
import datadog.trace.bootstrap.instrumentation.usm.MessageEncoder;
import java.net.Inet6Address;
import java.nio.Buffer;
import javax.net.ssl.SSLSocket;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class SslSocketInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("close").and(takesArguments(0))),
        SslSocketInstrumentation.class.getName() + "$CloseAdvice");
  }

  public static final class CloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void close(@Advice.This final SSLSocket socket) {
      boolean isIPv6 = socket.getLocalAddress() instanceof Inet6Address;
      Connection connection =
          new Connection(
              socket.getLocalAddress(),
              socket.getLocalPort(),
              socket.getInetAddress(),
              socket.getPort(),
              isIPv6);
      Buffer message =
          MessageEncoder.encode(MessageEncoder.MessageType.CLOSE_CONNECTION, connection);
      Extractor.Supplier.send(message);
    }
  }
}
