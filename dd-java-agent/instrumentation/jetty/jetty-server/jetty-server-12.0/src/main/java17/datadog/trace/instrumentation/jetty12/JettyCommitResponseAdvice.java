package datadog.trace.instrumentation.jetty12;

import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Response-phase AppSec blocking on {@code ChannelResponse.write(boolean, ByteBuffer, Callback)}:
 * when the response IG callbacks ask for blocking before the response is committed, writes the
 * blocking response instead and skips the original write.
 */
public class JettyCommitResponseAdvice {

  @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
  public static boolean /* skip */ onEnter(
      @Advice.This final Response response, @Advice.Argument(2) final Callback callback) {
    return JettyCommitResponseHelper.tryBlockOnCommit(response, callback);
  }
}
