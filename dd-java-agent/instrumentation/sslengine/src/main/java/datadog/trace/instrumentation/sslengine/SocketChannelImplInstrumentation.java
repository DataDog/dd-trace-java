package datadog.trace.instrumentation.sslengine;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.usm.Connection;
import datadog.trace.bootstrap.instrumentation.usm.Extractor;
import datadog.trace.bootstrap.instrumentation.usm.MessageEncoder;
import datadog.trace.bootstrap.instrumentation.usm.Peer;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.nio.Buffer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class SocketChannelImplInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  public SocketChannelImplInstrumentation() {
    super("nio-socketchannel", "socketchannel");
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  /** Match any child class of the base abstract SocketChannel class. */
  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return namedNoneOf("java.nio.channels.SocketChannel")
        .and(extendsClass(named("java.nio.channels.SocketChannel")));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(namedOneOf("read", "write")),
        SocketChannelImplInstrumentation.class.getName() + "$MessageAdvice");
    transformation.applyAdvice(
        isMethod().and(named("implCloseSelectableChannel")),
        SocketChannelImplInstrumentation.class.getName() + "$CloseAdvice");
  }

  public static final class MessageAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onMessage(
        @Advice.FieldValue("localAddress") InetSocketAddress localSocketAddr,
        @Advice.FieldValue("remoteAddress") InetSocketAddress remoteSocketAddr)
        throws IOException {

      if (localSocketAddr == null || remoteSocketAddr == null) {
        return;
      }
      boolean isIPv6 = localSocketAddr.getAddress() instanceof Inet6Address;
      Connection connection =
          new Connection(
              localSocketAddr.getAddress(),
              localSocketAddr.getPort(),
              remoteSocketAddr.getAddress(),
              remoteSocketAddr.getPort(),
              isIPv6);

      Peer peer = new Peer(remoteSocketAddr.getHostString(), remoteSocketAddr.getPort());
      Buffer message = MessageEncoder.encode(MessageEncoder.CONNECTION_BY_PEER, connection, peer);
      Extractor.Supplier.send(message);
    }
  }

  public static final class CloseAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void close(
        @Advice.FieldValue("localAddress") InetSocketAddress localSocketAddr,
        @Advice.FieldValue("remoteAddress") InetSocketAddress remoteSocketAddr)
        throws IOException {

      // We use local fields (@Advice.FieldValue) directly since for a closed socket,
      // the getLocalAddress and getRemoteAddress methods of the SocketChannel will throw an
      // exception

      if (localSocketAddr == null || remoteSocketAddr == null) {
        return;
      }
      boolean isIPv6 = localSocketAddr.getAddress() instanceof Inet6Address;
      Connection connection =
          new Connection(
              localSocketAddr.getAddress(),
              localSocketAddr.getPort(),
              remoteSocketAddr.getAddress(),
              remoteSocketAddr.getPort(),
              isIPv6);
      Buffer message = MessageEncoder.encode(MessageEncoder.CLOSE_CONNECTION, connection);
      Extractor.Supplier.send(message);
    }
  }
}
