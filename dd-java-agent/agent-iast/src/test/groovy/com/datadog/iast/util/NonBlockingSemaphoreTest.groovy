package com.datadog.iast.util

import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@CompileDynamic
class NonBlockingSemaphoreTest extends DDSpecification {

  void 'test that the semaphore controls access to a shared resource (#permitCount)'(final int permitCount) {
    given:
    final int threads = 100
    final semaphore = NonBlockingSemaphore.withPermitCount(permitCount)
    final latch = new CountDownLatch(threads)
    final executors = Executors.newFixedThreadPool(threads)

    when:
    final acquired = (1..threads).collect {
      executors.submit({
        latch.countDown()
        if (semaphore.acquire()) {
          TimeUnit.MILLISECONDS.sleep(100)
          semaphore.release()
          return 1
        }
        return 0
      } as Callable<Integer>)
    }.collect { it.get() }.sum()

    then:
    acquired >= permitCount
    acquired < threads
    semaphore.available() == permitCount

    where:
    permitCount | _
    1           | _
    2           | _
  }

  void 'there is resource starvation if the semaphore is not released (#permitCount)'(final int permitCount) {
    given:
    final int threads = 100
    final semaphore = NonBlockingSemaphore.withPermitCount(permitCount)
    final latch = new CountDownLatch(threads)
    final executors = Executors.newFixedThreadPool(threads)

    when:
    final acquired = (1..threads).collect {
      executors.submit ({
        latch.countDown()
        if (semaphore.acquire()) {
          TimeUnit.MILLISECONDS.sleep(100)
          return 1
        }
        return 0
      } as Callable<Integer>)
    }.collect { it.get() }.sum()

    then:
    acquired == permitCount
    semaphore.available() == 0

    where:
    permitCount | _
    1           | _
    2           | _
  }

  void 'reset helps recover when there is starvation (#permitCount)'(final int permitCount) {
    given:
    final semaphore = NonBlockingSemaphore.withPermitCount(permitCount)

    when:
    (1..permitCount).each { semaphore.acquire() }

    then:
    semaphore.available() == 0

    when:
    semaphore.reset()

    then:
    semaphore.available() == permitCount

    where:
    permitCount | _
    1           | _
    2           | _
  }

  void 'unlimited semaphore is always available'() {
    given:
    final int threads = 100
    final semaphore = NonBlockingSemaphore.unlimited()
    final latch = new CountDownLatch(threads)
    final executors = Executors.newFixedThreadPool(threads)

    when:
    final acquired = (1..threads).collect {
      executors.submit({
        latch.countDown()
        if (semaphore.acquire()) {
          TimeUnit.MILLISECONDS.sleep(100)
          semaphore.release()
          return 1
        }
        return 0
      } as Callable<Integer>)
    }.collect { it.get() }.sum()

    then:
    acquired == threads
    semaphore.available() == Integer.MAX_VALUE
  }
}
