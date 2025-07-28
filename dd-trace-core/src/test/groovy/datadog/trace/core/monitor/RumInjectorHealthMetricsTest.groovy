package datadog.trace.core.monitor

import datadog.trace.api.StatsDClient
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RumInjectorHealthMetricsTest extends Specification {
  def statsD = Mock(StatsDClient)

  @Subject
  def healthMetrics = new DefaultRumInjectorHealthMetrics(statsD)

  def "test onInjectionSucceed"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new DefaultRumInjectorHealthMetrics(new Latched(statsD, latch), 10, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onInjectionSucceed()
    latch.await(5, TimeUnit.SECONDS)

    then:
    1 * statsD.count('rum.injection.succeed', 1, _)
    0 * _

    cleanup:
    healthMetrics.close()
  }

  def "test onInjectionFailed"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new DefaultRumInjectorHealthMetrics(new Latched(statsD, latch), 10, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onInjectionFailed()
    latch.await(5, TimeUnit.SECONDS)

    then:
    1 * statsD.count('rum.injection.failed', 1, _)
    0 * _

    cleanup:
    healthMetrics.close()
  }

  def "test onInjectionSkipped"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new DefaultRumInjectorHealthMetrics(new Latched(statsD, latch), 10, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onInjectionSkipped()
    latch.await(5, TimeUnit.SECONDS)

    then:
    1 * statsD.count('rum.injection.skipped', 1, _)
    0 * _

    cleanup:
    healthMetrics.close()
  }

  def "test multiple events"() {
    setup:
    def latch = new CountDownLatch(3) // expecting 3 metric types
    def healthMetrics = new DefaultRumInjectorHealthMetrics(new Latched(statsD, latch), 10, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onInjectionSucceed()
    healthMetrics.onInjectionFailed()
    healthMetrics.onInjectionSkipped()
    latch.await(5, TimeUnit.SECONDS)

    then:
    1 * statsD.count('rum.injection.succeed', 1, _)
    1 * statsD.count('rum.injection.failed', 1, _)
    1 * statsD.count('rum.injection.skipped', 1, _)
    0 * _

    cleanup:
    healthMetrics.close()
  }

  def "test summary"() {
    when:
    healthMetrics.onInjectionSucceed()
    healthMetrics.onInjectionFailed()
    healthMetrics.onInjectionSkipped()
    def summary = healthMetrics.summary()

    then:
    summary.contains("injectionSucceed=1")
    summary.contains("injectionFailed=1")
    summary.contains("injectionSkipped=1")
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
