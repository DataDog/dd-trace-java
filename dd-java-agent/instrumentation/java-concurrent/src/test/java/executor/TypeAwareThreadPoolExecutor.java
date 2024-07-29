package executor;

import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class TypeAwareThreadPoolExecutor extends ThreadPoolExecutor {
  public TypeAwareThreadPoolExecutor() {
    super(2, 2, 0, TimeUnit.MICROSECONDS, new ArrayBlockingQueue<Runnable>(100));
  }

  @Override
  public boolean remove(Runnable task) {
    assertNotWrapper(task);
    return super.remove(task);
  }

  @Override
  protected void beforeExecute(Thread t, Runnable r) {
    assertNotWrapper(r);
  }

  @Override
  protected void afterExecute(Runnable r, Throwable t) {
    assertNotWrapper(r);
  }

  private void assertNotWrapper(Runnable r) {
    assert !(r instanceof Wrapper);
  }
}
