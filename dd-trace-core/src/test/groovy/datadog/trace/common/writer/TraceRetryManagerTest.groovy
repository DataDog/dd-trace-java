package datadog.trace.common.writer

import datadog.trace.api.Config
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Timeout

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Timeout(10)
class TraceRetryManagerTest extends DDCoreSpecification {

  def "enqueuing payload for retry increments counter"() {
    setup:
    def mockApi = Mock(RemoteApi)
    def mockConfig = Mock(Config) {
      getRetryQueueSize() >> 10
      getRetryBackoffInitialMs() >> 100L
      getRetryBackoffMaxMs() >> 1000L
    }
    def retryManager = new TraceRetryManager(mockApi, mockConfig)
    def payload = Mock(Payload) {
      traceCount() >> 5
    }

    when:
    retryManager.enqueue(payload, 0)

    then:
    retryManager.retriesEnqueued == 1
    retryManager.queueSize == 1
  }

  def "full queue drops payloads and increments dropped counter"() {
    setup:
    def mockApi = Mock(RemoteApi)
    def mockConfig = Mock(Config) {
      getRetryQueueSize() >> 2
      getRetryBackoffInitialMs() >> 100L
      getRetryBackoffMaxMs() >> 1000L
    }
    def retryManager = new TraceRetryManager(mockApi, mockConfig)
    def payload1 = Mock(Payload) { traceCount() >> 1 }
    def payload2 = Mock(Payload) { traceCount() >> 2 }
    def payload3 = Mock(Payload) { traceCount() >> 3 }

    when:
    retryManager.enqueue(payload1, 0)
    retryManager.enqueue(payload2, 0)
    retryManager.enqueue(payload3, 0)

    then:
    retryManager.retriesEnqueued == 2
    retryManager.retriesDroppedQueueFull == 1
    retryManager.queueSize == 2
  }

  def "successful retry increments success counter"() {
    setup:
    def mockApi = Mock(RemoteApi)
    def successResponse = RemoteApi.Response.success(200)

    mockApi.sendSerializedTraces(_) >> successResponse

    def mockConfig = Mock(Config) {
      getRetryQueueSize() >> 10
      getRetryBackoffInitialMs() >> 50L
      getRetryBackoffMaxMs() >> 1000L
    }
    def retryManager = new TraceRetryManager(mockApi, mockConfig)
    def payload = Mock(Payload) {
      traceCount() >> 5
    }

    retryManager.start()

    when:
    retryManager.enqueue(payload, 0)
    // Wait for retry to process
    Thread.sleep(200)

    then:
    retryManager.retriesResubmitted >= 1

    cleanup:
    retryManager.close()
  }

  def "429 response triggers exponential backoff and re-enqueue"() {
    setup:
    def mockApi = Mock(RemoteApi)
    def mockPayload = Mock(Payload) {
      traceCount() >> 5
    }
    def response429 = RemoteApi.Response.retryable(429, mockPayload)
    def successResponse = RemoteApi.Response.success(200)
    def callCount = new AtomicInteger(0)

    // First call returns 429, second call returns success
    mockApi.sendSerializedTraces(_) >> { args ->
      callCount.incrementAndGet() == 1 ? response429 : successResponse
    }

    def mockConfig = Mock(Config) {
      getRetryQueueSize() >> 10
      getRetryBackoffInitialMs() >> 50L
      getRetryBackoffMaxMs() >> 200L
    }
    def retryManager = new TraceRetryManager(mockApi, mockConfig)

    retryManager.start()

    when:
    retryManager.enqueue(mockPayload, 0)
    // Wait for initial retry + re-queue + second retry
    Thread.sleep(500)

    then:
    callCount.get() >= 2
    retryManager.retries429Count >= 1
    retryManager.retriesResubmitted >= 1

    cleanup:
    retryManager.close()
  }

  def "non-429 error drops payload and does not re-enqueue"() {
    setup:
    def mockApi = Mock(RemoteApi)
    def errorResponse = RemoteApi.Response.failed(500)
    def callCount = new AtomicInteger(0)

    mockApi.sendSerializedTraces(_) >> {
      callCount.incrementAndGet()
      return errorResponse
    }

    def mockConfig = Mock(Config) {
      getRetryQueueSize() >> 10
      getRetryBackoffInitialMs() >> 50L
      getRetryBackoffMaxMs() >> 200L
    }
    def retryManager = new TraceRetryManager(mockApi, mockConfig)
    def payload = Mock(Payload) {
      traceCount() >> 5
    }

    retryManager.start()

    when:
    retryManager.enqueue(payload, 0)
    // Wait for retry to process
    Thread.sleep(200)

    then:
    callCount.get() == 1
    retryManager.retriesResubmitted == 0
    retryManager.retriesExhausted == 1
    retryManager.queueSize == 0

    cleanup:
    retryManager.close()
  }

  def "close interrupts retry worker"() {
    setup:
    def mockApi = Mock(RemoteApi)
    def mockConfig = Mock(Config) {
      getRetryQueueSize() >> 10
      getRetryBackoffInitialMs() >> 100L
      getRetryBackoffMaxMs() >> 1000L
    }
    def retryManager = new TraceRetryManager(mockApi, mockConfig)

    when:
    retryManager.start()
    Thread.sleep(50)
    retryManager.close()
    Thread.sleep(100)

    then:
    !retryManager.@retryWorker.isAlive()
  }
}
