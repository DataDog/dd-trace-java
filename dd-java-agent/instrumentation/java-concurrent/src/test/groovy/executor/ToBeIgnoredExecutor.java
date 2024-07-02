package executor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ToBeIgnoredExecutor extends ThreadPoolExecutor {
  public ToBeIgnoredExecutor() {
    super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10));
  }
}
