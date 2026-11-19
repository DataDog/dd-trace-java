package executor;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;

public final class NettyTestExecutors {
  private NettyTestExecutors() {}

  public static void shutdown(EventExecutorGroup executor) {
    if (executor == null) {
      return;
    }
    EventExecutorGroup parent =
        executor instanceof EventExecutor ? ((EventExecutor) executor).parent() : null;
    Future<?> terminated = executor.shutdownGracefully(0, 5, SECONDS);
    Future<?> parentTerminated = parent == null ? null : parent.shutdownGracefully(0, 5, SECONDS);
    assertTrue(terminated.awaitUninterruptibly(10, SECONDS), "Netty executor did not terminate");
    if (parentTerminated != null) {
      assertTrue(
          parentTerminated.awaitUninterruptibly(10, SECONDS), "Netty parent did not terminate");
    }
  }
}
