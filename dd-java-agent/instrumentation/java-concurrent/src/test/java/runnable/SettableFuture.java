package runnable;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

public class SettableFuture extends FutureTask<String> {
  SettableFuture() {
    super(
        new Callable<String>() {
          @Override
          public String call() throws Exception {
            return "async result";
          }
        });
  }

  public void set(String value) {
    super.set(value);
  }

  public void setException(Throwable cause) {
    super.setException(cause);
  }
}
