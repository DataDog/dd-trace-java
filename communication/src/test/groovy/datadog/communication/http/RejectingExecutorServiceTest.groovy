package datadog.communication.http

import org.junit.jupiter.api.Test

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

import static groovy.test.GroovyAssert.shouldFail

class RejectingExecutorServiceTest {
  ExecutorService executorService = new RejectingExecutorService()

  @Test
  void 'execute throws exception'() {
    shouldFail(RejectedExecutionException) {
      executorService.execute({})
    }
  }

  @Test
  void 'exercise other methods'() {
    assert executorService.terminated
    assert executorService.awaitTermination(0, TimeUnit.SECONDS)
    executorService.shutdown()
    assert executorService.shutdownNow().empty
  }
}
