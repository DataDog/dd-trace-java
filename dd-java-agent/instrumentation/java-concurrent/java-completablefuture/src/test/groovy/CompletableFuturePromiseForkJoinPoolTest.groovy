import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

class CompletableFuturePromiseForkJoinPoolTest extends CompletableFuturePromiseTest {
  @Override
  Executor executor() {
    return Executors.newWorkStealingPool(3) // Three is the magic number
  }
}
