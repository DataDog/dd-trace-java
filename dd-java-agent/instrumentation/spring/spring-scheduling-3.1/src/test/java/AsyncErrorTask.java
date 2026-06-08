import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;

public class AsyncErrorTask {

  public static final String ERROR_MESSAGE = "async boom";

  @Async
  public CompletableFuture<Integer> asyncThrow() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }
}
