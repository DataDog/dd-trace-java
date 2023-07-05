package datadog.trace.instrumentation.sslengine;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.usm.Extractor;
import datadog.trace.bootstrap.instrumentation.usm.MessageEncoder;
import datadog.trace.bootstrap.instrumentation.usm.Payload;
import datadog.trace.bootstrap.instrumentation.usm.Peer;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class SslEngineInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public SslEngineInstrumentation() {
    super("sslengine");
  }

  @Override
  public String instrumentedType() {
    return "javax.net.ssl.SSLEngine";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("wrap"))
            .and(takesArguments(2))
            .and(takesArgument(0, ByteBuffer[].class)),
        SslEngineInstrumentation.class.getName() + "$WrapAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("unwrap"))
            .and(takesArguments(2))
            .and(takesArgument(1, ByteBuffer.class)),
        SslEngineInstrumentation.class.getName() + "$UnwrapAdvice");
  }

  public static final class WrapAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(
        @Advice.This final SSLEngine thiz,
        @Advice.Argument(0) final ByteBuffer[] srcs,
        @Advice.Return SSLEngineResult result) {

      // We want to skip the TLS handshake message, since we are not interested in capturing those,
      // because it doesn't contain any actual data

      // first condition - No source buffer - usually happens during handshake
      // second condition - Before accomplishing the handshake, the session doesn't have a session
      // id, as it is
      // generated during the handshake kickstart.
      if (srcs.length != 0 || thiz.getSession().getId().length == 0) {
        return;
      }
      if (result.bytesConsumed() > 0) {
        byte[] b = new byte[result.bytesConsumed()];
        int consumed = 0;
        for (int i = 0; i < srcs.length && consumed < b.length; i++) {
          int oldPos = srcs[i].position();
          srcs[i].position(srcs[i].arrayOffset());
          srcs[i].get(b, 0, oldPos);
          srcs[i].position(oldPos);
          consumed += oldPos;
        }

        Peer peer = new Peer(thiz.getPeerHost(), thiz.getPeerPort());
        Payload payload = new Payload(b, 0, b.length);
        Buffer message =
            MessageEncoder.encode(MessageEncoder.MessageType.ASYNC_PAYLOAD, peer, payload);
        Extractor.Supplier.send(message);
      }
    }
  }

  public static final class UnwrapAdvice {

      @Advice.OnMethodExit(suppress = Throwable.class)
      public static void unwrap(
          @Advice.This final SSLEngine thiz,
          @Advice.Argument(1) final ByteBuffer dst,
          @Advice.Return SSLEngineResult result) {

        // before accomplishing the handshake, the session doesn't have a session id, as it is
        // generated during the handshake kickstart.
        if (thiz.getSession().getId().length != 0) {
          return;
        }
        if (result.bytesProduced() > 0 && dst.limit() >= result.bytesProduced()) {
          byte[] b = new byte[result.bytesProduced()];
          int oldPos = dst.position();
          dst.position(dst.arrayOffset());
          dst.get(b, 0, result.bytesProduced());
          dst.position(oldPos);

          Peer peer = new Peer(thiz.getPeerHost(), thiz.getPeerPort());
          Payload payload = new Payload(b, 0, b.length);
          Buffer message =
              MessageEncoder.encode(MessageEncoder.MessageType.ASYNC_PAYLOAD, peer, payload);
          Extractor.Supplier.send(message);
        }
      }
    }
}
