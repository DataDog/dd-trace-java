package executor.rejectedexecutionhandler;

import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

public class SwallowingRejectedExecutionHandler
    implements RejectedExecutionHandler, io.netty.util.concurrent.RejectedExecutionHandler {
  @Override
  public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {}

  @Override
  public void rejected(Runnable task, SingleThreadEventExecutor executor) {}
}
