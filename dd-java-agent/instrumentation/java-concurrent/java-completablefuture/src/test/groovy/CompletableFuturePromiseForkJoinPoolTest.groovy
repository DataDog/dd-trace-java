import java.util.concurrent.Executor
import java.util.concurrent.Executors

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class CompletableFuturePromiseForkJoinPoolTest extends CompletableFuturePromiseTest {
  @Override
  Executor executor() {
    return Executors.newWorkStealingPool(3) // Three is the magic number
  }
}
