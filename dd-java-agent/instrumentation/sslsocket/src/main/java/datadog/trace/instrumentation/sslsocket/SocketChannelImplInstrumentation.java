package datadog.trace.instrumentation.sslsocket;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.usm.MessageEncoder;
import datadog.trace.bootstrap.instrumentation.usm.Peer;
import datadog.trace.bootstrap.instrumentation.usm.Connection;
import datadog.trace.bootstrap.instrumentation.usm.Extractor;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.Socket;
import java.nio.Buffer;
import java.nio.channels.SocketChannel;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

@AutoService(Instrumenter.class)
public final class SocketChannelImplInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  private static final Logger log =
      LoggerFactory.getLogger(SocketChannelImplInstrumentation.class);

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
        isMethod()
        .and(namedOneOf("read","write")),
        SocketChannelImplInstrumentation.class.getName() + "$MessageAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("close")),
        SocketChannelImplInstrumentation.class.getName() + "$CloseAdvice");

  }

  public static final class MessageAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onMessage(
        @Advice.This SocketChannel thiz) throws IOException {

      Socket sock = thiz.socket();
      String hostName = sock.getInetAddress().getHostName();
      boolean isIPv6 = sock.getLocalAddress() instanceof Inet6Address;
      Connection connection =
          new Connection(
              sock.getLocalAddress(),
              sock.getLocalPort(),
              sock.getInetAddress(),
              sock.getPort(),
              isIPv6);
      Peer peer = new Peer(hostName,sock.getPort());
      Buffer message = MessageEncoder.encode(MessageEncoder.MessageType.CONNECTION_BY_PEER,connection, peer);
      Extractor.Supplier.send(message);
      System.out.println("[host_message] sent a host message" );
    }
  }

  public static final class CloseAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void close(
        @Advice.This SocketChannel thiz) throws IOException {

      Socket sock = thiz.socket();
      boolean isIPv6 = sock.getLocalAddress() instanceof Inet6Address;
      Connection connection =
          new Connection(
              sock.getLocalAddress(),
              sock.getLocalPort(),
              sock.getInetAddress(),
              sock.getPort(),
              isIPv6);
      Buffer message = MessageEncoder.encode(MessageEncoder.MessageType.CLOSE_CONNECTION,connection);
      Extractor.Supplier.send(message);
      System.out.println("[close] sent a close message" );
    }
  }

}
