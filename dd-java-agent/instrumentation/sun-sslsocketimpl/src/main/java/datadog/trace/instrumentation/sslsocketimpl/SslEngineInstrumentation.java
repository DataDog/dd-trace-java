package datadog.trace.instrumentation.sslsocketimpl;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.UsmConnection;
import datadog.trace.bootstrap.instrumentation.api.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.api.UsmMessage;
import datadog.trace.bootstrap.instrumentation.api.UsmMessageFactory;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;


@AutoService(Instrumenter.class)
public final class SslEngineInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  private static final Logger log =
      LoggerFactory.getLogger(SslSocketImplStreamsInstrumentation.class);

  public SslEngineInstrumentation() {
    super("sslengine");
  }

  @Override
  public String instrumentedType(){
    return "javax.net.ssl.SSLEngine";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("wrap"))
            .and(takesArguments(2))
            .and(takesArgument(0,ByteBuffer[].class))
            .and(takesArgument(1, ByteBuffer.class)),
        SslEngineInstrumentation.class.getName() + "$WrapAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("unwrap"))
            .and(takesArguments(2))
            .and(takesArgument(0, ByteBuffer.class))
            .and(takesArgument(1, ByteBuffer.class)),
        SslEngineInstrumentation.class.getName() + "$UnwrapAdvice");
  }

  public final static class WrapAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(
        @Advice.This final SSLEngine thiz,
        @Advice.Argument(0) final ByteBuffer[] srcs,
        @Advice.Argument(1) final ByteBuffer dst,
        @Advice.Return SSLEngineResult result) throws NoSuchAlgorithmException, UnknownHostException {

      //no source buffer - usually happens during handshake
      if (srcs.length == 0){
        System.out.println("[wrap] no source buffers");
      }
      //before accomplishing the handshake, the session doesn't have a session id, as it is generated during the handshake kickstart.
      else if (thiz.getSession().getId().length == 0){
          System.out.println("[wrap] still handshaking");
      }
      else if (result.bytesConsumed() > 0){
        byte[] b = new byte[result.bytesConsumed()];
        int consumed = 0;
        System.out.println("[wrap] handling " + srcs.length + " buffers, consumed: " + result.bytesConsumed());
        for (int i=0;i<srcs.length && consumed<b.length;i++){
          int oldPos = srcs[i].position();
          srcs[i].position(srcs[i].arrayOffset());
          srcs[i].get(b, 0, oldPos);
          srcs[i].position(oldPos);
          consumed+=oldPos;
        }
        UsmConnection connection =
            new UsmConnection(
                InetAddress.getLoopbackAddress(),
                0,
                InetAddress.getLoopbackAddress(),
                thiz.getPeerPort(),
                false);
        UsmMessage message =
            UsmMessageFactory.Supplier.getPlainMessage(connection, thiz.getPeerHost(), b, 0, b.length);
        UsmExtractor.Supplier.send(message);
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
          System.out.println("[unwrap] processing dst buffer, produced: " + result.bytesProduced() + " dst limit: " +dst.limit() );
          byte[] b = new byte[result.bytesProduced()];
          int oldPos = dst.position();
          dst.position(dst.arrayOffset());
          dst.get(b, 0, result.bytesProduced());
          dst.position(oldPos);
          UsmConnection connection =
              new UsmConnection(
                  InetAddress.getLoopbackAddress(),
                  0,
                  InetAddress.getLoopbackAddress(),
                  thiz.getPeerPort(),
                  false);
          UsmMessage message =
              UsmMessageFactory.Supplier.getPlainMessage(connection,thiz.getPeerHost(), b, 0, b.length);
          UsmExtractor.Supplier.send(message);
        }
        else{
          System.out.println("[unwrap] invalid dst buffer, produced: " + result.bytesProduced() + " dst limit: " + dst.limit());
        }
      }
    }
  }
}
