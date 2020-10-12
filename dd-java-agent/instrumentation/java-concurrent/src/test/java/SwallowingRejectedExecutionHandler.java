import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

public class SwallowingRejectedExecutionHandler implements RejectedExecutionHandler {
  @Override
  public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {}
}
