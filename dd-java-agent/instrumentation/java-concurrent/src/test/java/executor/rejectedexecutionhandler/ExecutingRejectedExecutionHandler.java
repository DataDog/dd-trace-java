package executor.rejectedexecutionhandler;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

public class ExecutingRejectedExecutionHandler implements RejectedExecutionHandler {
  @Override
  public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
    if (!executor.isShutdown()) {
      r.run();
    }
  }
}
