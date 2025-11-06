package datadog.trace.util.queue

import datadog.trace.test.util.DDSpecification
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Supplier
import org.jctools.queues.MpscBlockingConsumerArrayQueue

class JctoolsWrapppersTest extends DDSpecification {

  def "should wrap the method #method to the jctools delegate #wrapperClass"() {
    setup:
    // will work for both wrapper classes
    def delegate = Mock(MpscBlockingConsumerArrayQueue)
    def queue = wrapperClass.newInstance(delegate) as NonBlockingQueue

    when:
    queue.invokeMethod(method, args.toArray())

    then:
    1 * delegate."$method"(*_)

    where:
    method     | args                  | wrapperClass
    "poll"     | []                    | JctoolsWrappedQueue
    "offer"    | ["test"]              | JctoolsWrappedQueue
    "capacity" | []                    | JctoolsWrappedQueue
    "peek"     | []                    | JctoolsWrappedQueue
    "drain"    | [Mock(Consumer)]      | JctoolsWrappedQueue
    "drain"    | [Mock(Consumer), 1]   | JctoolsWrappedQueue
    "fill"     | [Mock(Supplier), 1]   | JctoolsWrappedQueue
    "poll"     | []                    | JctoolsMpscBlockingConsumerWrappedQueue
    "offer"    | ["test"]              | JctoolsMpscBlockingConsumerWrappedQueue
    "capacity" | []                    | JctoolsMpscBlockingConsumerWrappedQueue
    "peek"     | []                    | JctoolsMpscBlockingConsumerWrappedQueue
    "drain"    | [Mock(Consumer)]      | JctoolsMpscBlockingConsumerWrappedQueue
    "drain"    | [Mock(Consumer), 1]   | JctoolsMpscBlockingConsumerWrappedQueue
    "fill"     | [Mock(Supplier), 1]   | JctoolsMpscBlockingConsumerWrappedQueue
    "poll"     | [1, TimeUnit.SECONDS] | JctoolsMpscBlockingConsumerWrappedQueue
    "take"     | []                    | JctoolsMpscBlockingConsumerWrappedQueue
  }
}
