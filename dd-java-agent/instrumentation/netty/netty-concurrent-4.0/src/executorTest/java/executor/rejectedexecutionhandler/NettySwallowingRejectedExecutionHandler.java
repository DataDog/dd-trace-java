package executor.rejectedexecutionhandler;

import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.SingleThreadEventExecutor;

public class NettySwallowingRejectedExecutionHandler implements RejectedExecutionHandler {
  @Override
  public void rejected(Runnable task, SingleThreadEventExecutor executor) {}
}
