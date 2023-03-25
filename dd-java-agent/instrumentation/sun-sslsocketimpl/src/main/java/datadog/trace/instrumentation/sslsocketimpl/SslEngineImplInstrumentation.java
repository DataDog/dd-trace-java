package datadog.trace.instrumentation.sslsocketimpl;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import javax.net.ssl.SSLEngine;

import datadog.trace.bootstrap.instrumentation.api.UsmConnection;
import datadog.trace.bootstrap.instrumentation.api.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.api.UsmMessage;
import datadog.trace.bootstrap.instrumentation.api.UsmMessageFactory;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngineResult;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;


@AutoService(Instrumenter.class)
public final class SslEngineImplInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  private static final Logger log =
      LoggerFactory.getLogger(SslSocketImplStreamsInstrumentation.class);

  public SslEngineImplInstrumentation() {
    super("sslengine");
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("javax.net.ssl.SSLEngine"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("wrap"))
            .and(takesArguments(2))
            .and(takesArgument(0,ByteBuffer[].class))
            .and(takesArgument(1, ByteBuffer.class)),
        SslEngineImplInstrumentation.class.getName() + "$WrapAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("unwrap"))
            .and(takesArguments(2))
            .and(takesArgument(0, ByteBuffer.class))
            .and(takesArgument(1, ByteBuffer.class)),
        SslEngineImplInstrumentation.class.getName() + "$UnwrapAdvice");
  }

  public final static class WrapAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(
        @Advice.This final SSLEngine thiz,
        @Advice.Argument(0) final ByteBuffer[] srcs,
        @Advice.Argument(1) final ByteBuffer dst,
        @Advice.Return SSLEngineResult result) throws NoSuchAlgorithmException, UnknownHostException {

      //before accomplishing the handshake, the session doesn't have a session id, as it is generated during the handshake kickstart.
       if (thiz.getSession().getId().length == 0){
          System.out.println("[wrap] still handshaking");
        }
       else {
         if (result.bytesConsumed() > 0 && srcs.length == 1 && srcs[0].limit() >= result.bytesConsumed()) {
           byte[] b = new byte[result.bytesConsumed()];
           int oldPos = srcs[0].position();
           srcs[0].position(srcs[0].arrayOffset());
           srcs[0].get(b, 0, result.bytesConsumed());
           srcs[0].position(oldPos);
           UsmConnection connection =
               new UsmConnection(
                   InetAddress.getLoopbackAddress(),
                   0,
                   InetAddress.getByName(thiz.getPeerHost()),
                   thiz.getPeerPort(),
                   false);
           UsmMessage message =
               UsmMessageFactory.Supplier.getRequestMessage(connection, b, 0, b.length);
           UsmExtractor.Supplier.send(message);
         } else if (srcs.length > 1) {
           System.out.println("[wrap] buffer is split");
         }
       }
    }
  }

  public final static class UnwrapAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void unwrap(
        @Advice.This final SSLEngine thiz,
        @Advice.Argument(0) final ByteBuffer src,
        @Advice.Argument(1) final ByteBuffer dst,
        @Advice.Return SSLEngineResult result) throws NoSuchAlgorithmException, UnknownHostException {

      //before accomplishing the handshake, the session doesn't have a session id, as it is generated during the handshake kickstart.
      if (thiz.getSession().getId().length == 0){
        System.out.println("[unwrap] still handshaking");
      }
      else {
        if (result.bytesProduced() > 0 && dst.limit() >= result.bytesProduced()) {
          byte[] b = new byte[result.bytesProduced()];
          int oldPos = dst.position();
          dst.position(dst.arrayOffset());
          dst.get(b, 0, result.bytesProduced());
          dst.position(oldPos);
          UsmConnection connection =
              new UsmConnection(
                  InetAddress.getLoopbackAddress(),
                  0,
                  InetAddress.getByName(thiz.getPeerHost()),
                  thiz.getPeerPort(),
                  false);
          UsmMessage message =
              UsmMessageFactory.Supplier.getRequestMessage(connection, b, 0, b.length);
          UsmExtractor.Supplier.send(message);
        }
      }
    }
  }
}
