package datadog.trace.instrumentation.sslengine;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.usm.Payload.MAX_HTTPS_BUFFER_SIZE;
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
      // because it doesn't contain any actual data.

      // first condition - no source buffer - usually happens during handshake
      // second condition - before accomplishing the handshake, the session doesn't have a session
      // id,
      // which is generated during the handshake kickstart.
      if (srcs.length == 0 || thiz.getSession().getId().length == 0) {
        return;
      }
      if (result.bytesConsumed() > 0) {
        // dst buffer size is the minimum between the current response size
        // and max supported buffer size by kernel side (MAX_HTTPS_BUFFER_SIZE)
        ByteBuffer dstBuffer =
            ByteBuffer.allocate(Math.min(result.bytesConsumed(), MAX_HTTPS_BUFFER_SIZE));
        int consumed = 0;
        // we iterate over all src buffers (might be more than 1 if the response is big
        // we copy up to MAX_HTTPS_BUFFER_SIZE bytes in total into the destination buffer that would
        // be sent to kernel via eRPC
        for (int i = 0; i < srcs.length && consumed <= dstBuffer.limit(); i++) {
          // store the original position of the original buffer to prevent data corruption
          int oldPos = srcs[i].position();
          // move the buffer current pointer to the beginning
          srcs[i].flip();

          // we have enough remaining space in the destination buffer for the entire src buffer
          if (srcs[i].remaining() <= dstBuffer.remaining()) {
            dstBuffer.put(srcs[i]);
          } else {
            // get a slice of the source ByteBuffer
            ByteBuffer slice = srcs[i].slice();

            // limit the slice to the amount of remaining available bytes in the destination buffer
            slice.limit(Math.min(slice.remaining(), dstBuffer.remaining()));

            // copy the slice into the destination buffer
            dstBuffer.put(slice);
          }
          // restore the original position in the src buffer
          srcs[i].position(oldPos);
          // update number of total copied bytes so far
          consumed += oldPos;
        }
        dstBuffer.flip();
        Peer peer = new Peer(thiz.getPeerHost(), thiz.getPeerPort());
        Payload payload = new Payload(dstBuffer.array(), 0, dstBuffer.limit());
        Buffer message = MessageEncoder.encode(MessageEncoder.ASYNC_PAYLOAD, peer, payload);
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
      if (thiz.getSession().getId().length == 0) {
        return;
      }
      if (result.bytesProduced() > 0 && dst.limit() >= result.bytesProduced()) {
        int bufferSize = Math.min(result.bytesProduced(),MAX_HTTPS_BUFFER_SIZE);
        byte[] b = new byte[bufferSize];
        int oldPos = dst.position();
        dst.position(dst.arrayOffset());
        dst.get(b, 0, bufferSize);
        dst.position(oldPos);

        Peer peer = new Peer(thiz.getPeerHost(), thiz.getPeerPort());
        Payload payload = new Payload(b, 0, b.length);
        Buffer message = MessageEncoder.encode(MessageEncoder.ASYNC_PAYLOAD, peer, payload);
        Extractor.Supplier.send(message);
      }
    }
  }
}
