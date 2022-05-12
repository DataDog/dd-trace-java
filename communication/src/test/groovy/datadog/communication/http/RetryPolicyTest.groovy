package datadog.communication.http

import spock.lang.Specification

class RetryPolicyTest extends Specification {

  def "test retry policies"() {
    setup:
    def retryPolicy = RetryPolicy.builder()
      .withMaxRetry(maxRetries)
      .withBackoff(delayMs, delayMultiplier)
      .build()

    def shouldRetries = []
    def backoffs = []
    def retry = 0

    when:
    while (retry <= maxRetries) {
      def shouldRetry = retryPolicy.shouldRetry(retry)
      shouldRetries << shouldRetry
      if(shouldRetry) {
        backoffs << retryPolicy.backoff(retry)
      }
      retry+=1
    }

    then:
    shouldRetries == expectedShouldRetries
    backoffs == expectedBackoffs

    where:
    maxRetries | delayMs | delayMultiplier | expectedShouldRetries | expectedBackoffs
    0  | 100 | 2.0 | [false] | []
    1  | 100 | 2.0 | [true, false] | [100]
    2  | 100 | 2.0 | [true, true, false] | [100, 200]
    3  | 100 | 2.0 | [true, true, true, false] | [100, 200, 400]
    4  | 100 | 2.0 | [true, true, true, true, false] | [100, 200, 400, 800]
    5  | 100 | 2.0 | [true, true, true, true, true, false] | [100, 200, 400, 800, 1600]
  }
}
