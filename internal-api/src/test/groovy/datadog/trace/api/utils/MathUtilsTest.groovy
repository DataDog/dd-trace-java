package datadog.trace.api.utils

import datadog.trace.test.util.DDSpecification

import java.util.concurrent.atomic.AtomicLong

class MathUtilsTest extends DDSpecification {

  // Testing multithreaded code is inherently tricky
  // This tests across multiple competing threads nothing goes wrong
  // Its probabilistic at best and doesnt prove correctness
  def "correct number of decrements happen across threads"() {
    given:
    AtomicLong trueCount = new AtomicLong(0)
    AtomicLong falseCount = new AtomicLong(0)
    AtomicLong variable = new AtomicLong(initialValue)
    Random random = new Random()
    List<Thread> threads = []
    numThreads.times {
      final localDecrements = decrementsPerThread
      final localMinimum = minimum
      threads.add(new Thread() {
        void run() {
          localDecrements.times {
            Thread.sleep(random.nextInt(5))
            boolean returnValue = MathUtils.boundedDecrement(variable, localMinimum)
            if (returnValue) {
              trueCount.incrementAndGet()
            } else {
              falseCount.incrementAndGet()
            }
          }
        }
      })
    }

    when:
    threads.each { it.start() }
    threads.each { it.join() }

    then:
    variable.get() == Math.max(minimum, initialValue - numThreads * decrementsPerThread)
    trueCount.get() == Math.min(initialValue - minimum, numThreads * decrementsPerThread)
    falseCount.get() == numThreads * decrementsPerThread - trueCount.get()

    where:
    numThreads | decrementsPerThread | initialValue | minimum
    1          | 800                 | 1000         | 500
    1          | 1100                | 1000         | 0
    10         | 100                 | 500          | 0
    10         | 1000                | 1000         | 500
  }
}
