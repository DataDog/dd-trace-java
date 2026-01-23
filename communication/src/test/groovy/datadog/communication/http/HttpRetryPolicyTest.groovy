package datadog.communication.http

import datadog.communication.http.okhttp.OkHttpResponse
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import spock.lang.Specification

class HttpRetryPolicyTest extends Specification {

  def "test exponential backoff is applied to retries"() {
    setup:
    def retryPolicy = new HttpRetryPolicy.Factory(maxRetries, delayMs, delayMultiplier).create()

    def shouldRetries = []
    def backoffs = []
    def retry = 0

    when:
    while (retry <= maxRetries) {
      def shouldRetry = retryPolicy.shouldRetry(null)
      shouldRetries << shouldRetry
      if (shouldRetry) {
        backoffs << retryPolicy.getBackoffDelay()
      }
      retry += 1
    }

    then:
    shouldRetries == expectedShouldRetries
    backoffs == expectedBackoffs

    where:
    maxRetries | delayMs | delayMultiplier | expectedShouldRetries                 | expectedBackoffs
    0          | 100     | 2.0             | [false]                               | []
    1          | 100     | 2.0             | [true, false]                         | [100]
    2          | 100     | 2.0             | [true, true, false]                   | [100, 200]
    3          | 100     | 2.0             | [true, true, true, false]             | [100, 200, 400]
    4          | 100     | 2.0             | [true, true, true, true, false]       | [100, 200, 400, 800]
    5          | 100     | 2.0             | [true, true, true, true, true, false] | [100, 200, 400, 800, 1600]
  }

  def "test there are #expectedRetries retries for response code #responseCode and rate limit header #rateLimitHeader"() {
    setup:
    def retryPolicy = new HttpRetryPolicy.Factory(5, 100, 2.0).create()

    def responseBuilder = new Response.Builder()
    .code(responseCode)
    .request(GroovyMock(Request))
    .protocol(Protocol.HTTP_1_1)
    .message("")
    if (rateLimitHeader != null) {
      responseBuilder.header("x-ratelimit-reset", rateLimitHeader)
    }
    def response = responseBuilder.build()

    when:
    def retries = 0
    while (retryPolicy.shouldRetry(OkHttpResponse.wrap(response))) {
      retries++
    }

    then:
    retries == expectedRetries

    where:
    responseCode | rateLimitHeader | expectedRetries
    200          | null            | 0
    404          | null            | 0
    429          | null            | 5
    429          | "corrupted"     | 5
    429          | "2"             | 1
    429          | "20"            | 0
    500          | null            | 5
    501          | null            | 5
  }

  def "test exceptions are retried: #exception with suppress interrupts #suppressInterrupts"() {
    setup:
    def retryPolicy = new HttpRetryPolicy.Factory(5, 100, 2.0, suppressInterrupts).create()

    expect:
    retryPolicy.shouldRetry(exception) == shouldRetry

    where:
    exception                      | suppressInterrupts | shouldRetry
    new NullPointerException()     | false              | false
    new IllegalArgumentException() | false              | false
    new ConnectException()         | false              | true
    new InterruptedIOException()   | false              | false
    new InterruptedIOException()   | true               | true
    new InterruptedException()     | false              | false
    new InterruptedException()     | true               | true
  }

  def "test interrupt flag is preserved when suppressing interrupts"() {
    setup:
    def retryPolicy = new HttpRetryPolicy.Factory(5, 100, 2.0, true).create()

    when:
    retryPolicy.shouldRetry(new InterruptedException())
    retryPolicy.close()

    then:
    Thread.interrupted()
  }

  def "test interrupt flag is preserved if interrupted while backing off"() {
    setup:
    boolean[] b = new boolean[2]

    Runnable r = () -> {
      def retryPolicy = new HttpRetryPolicy.Factory(5, 1000, 2.0, true).create()
      retryPolicy.backoff()

      b[0] = Thread.currentThread().isInterrupted()
      retryPolicy.close()
      b[1] = Thread.interrupted()
    }
    Thread t = new Thread(r, "test-http-retry-policy-interrupts")

    when:
    t.start()
    t.interrupt()
    t.join()

    then:
    !b[0] // before retry policy is closed, the thread should not be interrupted: interrupts are suppressed
    b[1] // after retry policy is closed, the thread should be interrupted: interrupt flag should be restored
  }
}
