import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.scheduling.annotation.Async;

public class AsyncTask {

  @Async
  public CompletableFuture<Integer> async() {
    return CompletableFuture.completedFuture(ThreadLocalRandom.current().nextInt());
  }
}
