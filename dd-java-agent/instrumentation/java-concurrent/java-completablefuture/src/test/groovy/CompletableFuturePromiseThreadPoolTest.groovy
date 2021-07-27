import java.util.concurrent.Executor
import java.util.concurrent.Executors

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class CompletableFuturePromiseThreadPoolTest extends CompletableFuturePromiseTest {
  @Override
  Executor executor() {
    return Executors.newFixedThreadPool(3) // Three is the magic number
  }
}
