import datadog.trace.api.Trace;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.scheduling.annotation.Async;

public class AsyncTask {

  @Async
  public CompletableFuture<Integer> async() {
    return CompletableFuture.completedFuture(getInt());
  }

  @Async
  public CompletableFuture<Void> asyncThrow() {
    throw new RuntimeException("Datadog async repro");
  }

  @Trace
  public int getInt() {
    return ThreadLocalRandom.current().nextInt();
  }
}
