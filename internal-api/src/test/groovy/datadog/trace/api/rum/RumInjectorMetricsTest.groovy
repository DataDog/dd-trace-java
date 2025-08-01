package datadog.trace.api.rum

import datadog.trace.api.StatsDClient
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RumInjectorMetricsTest extends Specification {
  def statsD = Mock(StatsDClient)

  @Subject
  def metrics = new RumInjectorMetrics(statsD)

  def "test onInjectionSucceed"() {
    setup:
    def latch = new CountDownLatch(1)
    def metrics = new RumInjectorMetrics(new Latched(statsD, latch), 10, TimeUnit.MILLISECONDS)
    metrics.start()

    when:
    metrics.onInjectionSucceed()
    latch.await(5, TimeUnit.SECONDS)

    then:
    1 * statsD.count('rum.injection.succeed', 1, _)
    0 * _

    cleanup:
    metrics.close()
  }

  def "test onInjectionFailed"() {
    setup:
    def latch = new CountDownLatch(1)
    def metrics = new RumInjectorMetrics(new Latched(statsD, latch), 10, TimeUnit.MILLISECONDS)
    metrics.start()

    when:
    metrics.onInjectionFailed()
    latch.await(5, TimeUnit.SECONDS)

    then:
    1 * statsD.count('rum.injection.failed', 1, _)
    0 * _

    cleanup:
    metrics.close()
  }

  def "test onInjectionSkipped"() {
    setup:
    def latch = new CountDownLatch(1)
    def metrics = new RumInjectorMetrics(new Latched(statsD, latch), 10, TimeUnit.MILLISECONDS)
    metrics.start()

    when:
    metrics.onInjectionSkipped()
    latch.await(5, TimeUnit.SECONDS)

    then:
    1 * statsD.count('rum.injection.skipped', 1, _)
    0 * _

    cleanup:
    metrics.close()
  }

  def "test onContentSecurityPolicyDetected"() {
    setup:
    def latch = new CountDownLatch(1)
    def metrics = new RumInjectorMetrics(new Latched(statsD, latch), 10, TimeUnit.MILLISECONDS)
    metrics.start()

    when:
    metrics.onContentSecurityPolicyDetected()
    latch.await(5, TimeUnit.SECONDS)

    then:
    1 * statsD.count('rum.injection.content_security_policy', 1, _)
    0 * _

    cleanup:
    metrics.close()
  }

  def "test onInjectionResponseSize with multiple sizes"() {
    when:
    metrics.onInjectionResponseSize(512)
    metrics.onInjectionResponseSize(2048)
    metrics.onInjectionResponseSize(256)

    then:
    1 * statsD.distribution('rum.injection.response.bytes', 512, _)
    1 * statsD.distribution('rum.injection.response.bytes', 2048, _)
    1 * statsD.distribution('rum.injection.response.bytes', 256, _)
    0 * _
  }

  def "test flushing multiple events"() {
    setup:
    def latch = new CountDownLatch(4) // expecting 4 metric types
    def metrics = new RumInjectorMetrics(new Latched(statsD, latch), 10, TimeUnit.MILLISECONDS)
    metrics.start()

    when:
    metrics.onInjectionSucceed()
    metrics.onInjectionFailed()
    metrics.onInjectionSkipped()
    metrics.onContentSecurityPolicyDetected()
    latch.await(5, TimeUnit.SECONDS)

    then:
    1 * statsD.count('rum.injection.succeed', 1, _)
    1 * statsD.count('rum.injection.failed', 1, _)
    1 * statsD.count('rum.injection.skipped', 1, _)
    1 * statsD.count('rum.injection.content_security_policy', 1, _)
    0 * _

    cleanup:
    metrics.close()
  }

  def "test that flushing only reports non-zero deltas"() {
    setup:
    def latch = new CountDownLatch(1) // expecting only 1 metric call (non-zero delta)
    def metrics = new RumInjectorMetrics(new Latched(statsD, latch), 10, TimeUnit.MILLISECONDS)
    metrics.start()

    when:
    metrics.onInjectionSucceed()
    metrics.onInjectionSucceed()
    latch.await(5, TimeUnit.SECONDS)

    then:
    1 * statsD.count('rum.injection.succeed', 2, _)
    // should not be called since they have delta of 0
    0 * statsD.count('rum.injection.failed', _, _)
    0 * statsD.count('rum.injection.skipped', _, _)
    0 * statsD.count('rum.injection.content_security_policy', _, _)
    0 * _

    cleanup:
    metrics.close()
  }

  def "test summary with multiple events"() {
    when:
    metrics.onInjectionSucceed()
    metrics.onInjectionFailed()
    metrics.onInjectionSucceed()
    metrics.onInjectionFailed()
    metrics.onInjectionSucceed()
    metrics.onInjectionSkipped()
    metrics.onContentSecurityPolicyDetected()
    metrics.onContentSecurityPolicyDetected()
    def summary = metrics.summary()

    then:
    summary.contains("injectionSucceed=3")
    summary.contains("injectionFailed=2")
    summary.contains("injectionSkipped=1")
    summary.contains("contentSecurityPolicyDetected=2")
    0 * _
  }

  def "test metrics start at zero"() {
    when:
    def summary = metrics.summary()

    then:
    summary.contains("injectionSucceed=0")
    summary.contains("injectionFailed=0")
    summary.contains("injectionSkipped=0")
    summary.contains("contentSecurityPolicyDetected=0")
    0 * _
  }

  // taken from HealthMetricsTest
  private static class Latched implements StatsDClient {
    final StatsDClient delegate
    final CountDownLatch latch

    Latched(StatsDClient delegate, CountDownLatch latch) {
      this.delegate = delegate
      this.latch = latch
    }

    @Override
    void incrementCounter(String metricName, String... tags) {
      try {
        delegate.incrementCounter(metricName, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void count(String metricName, long delta, String... tags) {
      try {
        delegate.count(metricName, delta, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void gauge(String metricName, long value, String... tags) {
      try {
        delegate.gauge(metricName, value, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void gauge(String metricName, double value, String... tags) {
      try {
        delegate.gauge(metricName, value, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void histogram(String metricName, long value, String... tags) {
      try {
        delegate.histogram(metricName, value, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void histogram(String metricName, double value, String... tags) {
      try {
        delegate.histogram(metricName, value, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void distribution(String metricName, long value, String... tags) {
      try {
        delegate.distribution(metricName, value, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void distribution(String metricName, double value, String... tags) {
      try {
        delegate.distribution(metricName, value, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void serviceCheck(String serviceCheckName, String status, String message, String... tags) {
      try {
        delegate.serviceCheck(serviceCheckName, status, message, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void error(Exception error) {
      try {
        delegate.error(error)
      } finally {
        latch.countDown()
      }
    }

    @Override
    int getErrorCount() {
      try {
        return delegate.getErrorCount()
      } finally {
        latch.countDown()
      }
    }

    @Override
    void close() {
      try {
        delegate.close()
      } finally {
        latch.countDown()
      }
    }
  }
}
