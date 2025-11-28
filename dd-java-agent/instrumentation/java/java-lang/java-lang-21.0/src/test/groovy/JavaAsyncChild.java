import datadog.trace.api.Trace;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public class JavaAsyncChild implements Runnable, Callable<Void> {
  private final AtomicBoolean blockThread;
  private final boolean doTraceableWork;

  public JavaAsyncChild() {
    this(true, false);
  }

  public JavaAsyncChild(final boolean doTraceableWork, final boolean blockThread) {
    this.doTraceableWork = doTraceableWork;
    this.blockThread = new AtomicBoolean(blockThread);
  }

  public void unblock() {
    blockThread.set(false);
  }

  @Override
  public void run() {
    runImpl();
  }

  @Override
  public Void call() {
    runImpl();
    return null;
  }

  private void runImpl() {
    while (blockThread.get()) {
      // busy-wait to block thread
    }
    if (doTraceableWork) {
      asyncChild();
    }
  }

  @Trace(operationName = "asyncChild")
  private void asyncChild() {}
}
