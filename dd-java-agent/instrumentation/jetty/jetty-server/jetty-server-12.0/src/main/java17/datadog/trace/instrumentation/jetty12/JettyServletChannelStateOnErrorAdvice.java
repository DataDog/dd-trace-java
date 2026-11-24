package datadog.trace.instrumentation.jetty12;

import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.ee10.servlet.ServletChannelState;

/**
 * Exit of {@code ServletChannelState.onError(Throwable)}: when Jetty decided not to abort, lets
 * {@link JettyAsyncBlockCompletion} complete an async request suspended by a blocking response.
 */
public class JettyServletChannelStateOnErrorAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.This final ServletChannelState state,
      @Advice.Argument(0) final Throwable failure,
      @Advice.Return final boolean abort) {
    if (!abort) {
      JettyAsyncBlockCompletion.completeSuspendedBlock(state, failure);
    }
  }
}
