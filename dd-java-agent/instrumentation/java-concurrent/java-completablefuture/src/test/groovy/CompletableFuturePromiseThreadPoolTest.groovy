import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

class CompletableFuturePromiseThreadPoolTest extends CompletableFuturePromiseTest {
  @Override
  Executor executor() {
    return Executors.newFixedThreadPool(3) // Three is the magic number
  }
}
