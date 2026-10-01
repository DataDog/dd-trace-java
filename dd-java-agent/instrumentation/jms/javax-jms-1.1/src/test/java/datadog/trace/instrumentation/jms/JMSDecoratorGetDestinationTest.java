package datadog.trace.instrumentation.jms;

import static datadog.trace.instrumentation.jms.JMSDecorator.PRODUCER_DECORATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.jms.Destination;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueSender;
import javax.jms.Topic;
import javax.jms.TopicPublisher;
import org.junit.jupiter.api.Test;

/**
 * How {@code getDestination} copes with producers whose {@code getDestination} is unimplemented
 * ({@code <=1.1}) or legitimately unbound (an anonymous producer).
 */
class JMSDecoratorGetDestinationTest {

  interface GetDestinationAnswer {
    Destination get() throws Throwable;
  }

  /**
   * {@code Proxy} caches one generated class per interface list and class loader, so two instances
   * built from the same {@code interfaces} array share a class — exactly the real-world case the
   * latch is keyed on: every instance of one driver class has the same defect.
   */
  private static MessageProducer producer(
      Class<?>[] interfaces, AtomicInteger calls, GetDestinationAnswer answer, Object fallback) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "getDestination":
              calls.incrementAndGet();
              return answer.get();
            case "getQueue":
            case "getTopic":
              return fallback;
            default:
              return null;
          }
        };
    return (MessageProducer)
        Proxy.newProxyInstance(
            JMSDecoratorGetDestinationTest.class.getClassLoader(), interfaces, handler);
  }

  private static AbstractMethodError unimplemented(Object target) {
    // the exact HotSpot message format `isNamedIn` attributes to the receiver class
    return new AbstractMethodError(
        "Receiver class "
            + target.getClass().getName()
            + " does not define or inherit an implementation of the resolved method 'abstract"
            + " javax.jms.Destination getDestination()' of interface javax.jms.MessageProducer.");
  }

  @Test
  void returnsTheRealDestinationWhenAvailable() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Queue queue =
        (Queue)
            Proxy.newProxyInstance(
                JMSDecoratorGetDestinationTest.class.getClassLoader(),
                new Class<?>[] {Queue.class},
                (proxy, method, args) -> null);
    MessageProducer producer =
        producer(new Class<?>[] {MessageProducer.class}, calls, () -> queue, null);

    assertSame(queue, PRODUCER_DECORATE.getDestination(producer));
    assertEquals(1, calls.get());
  }

  @Test
  void anAnonymousProducersRealNullPassesThroughWithoutTheFallback() throws Exception {
    // a producer created with an unidentified destination (session.createProducer(null))
    // legitimately returns null; it must not be confused with an unimplemented getDestination
    AtomicInteger calls = new AtomicInteger();
    MessageProducer producer =
        producer(new Class<?>[] {MessageProducer.class}, calls, () -> null, null);

    assertNull(PRODUCER_DECORATE.getDestination(producer));
    assertEquals(1, calls.get());
  }

  @Test
  void fallsBackToGetQueueAndLatchesTheClass() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Queue queue =
        (Queue)
            Proxy.newProxyInstance(
                JMSDecoratorGetDestinationTest.class.getClassLoader(),
                new Class<?>[] {Queue.class},
                (proxy, method, args) -> null);
    AtomicReference<MessageProducer> self = new AtomicReference<>();
    MessageProducer sender =
        producer(
            new Class<?>[] {QueueSender.class},
            calls,
            () -> {
              throw unimplemented(self.get());
            },
            queue);
    self.set(sender);

    assertSame(queue, PRODUCER_DECORATE.getDestination(sender));
    assertEquals(1, calls.get());

    // a second call on the same class is skipped by the latch, not re-attempted
    assertSame(queue, PRODUCER_DECORATE.getDestination(sender));
    assertEquals(1, calls.get());
  }

  @Test
  void fallsBackToGetTopicForATopicPublisher() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Topic topic =
        (Topic)
            Proxy.newProxyInstance(
                JMSDecoratorGetDestinationTest.class.getClassLoader(),
                new Class<?>[] {Topic.class},
                (proxy, method, args) -> null);
    AtomicReference<MessageProducer> self = new AtomicReference<>();
    MessageProducer publisher =
        producer(
            new Class<?>[] {TopicPublisher.class},
            calls,
            () -> {
              throw unimplemented(self.get());
            },
            topic);
    self.set(publisher);

    assertSame(topic, PRODUCER_DECORATE.getDestination(publisher));
    assertEquals(1, calls.get());
  }

  @Test
  void aDifferentClassIsUnaffectedByAnotherClassesLatch() throws Exception {
    // latch one QueueSender class
    AtomicInteger latchedCalls = new AtomicInteger();
    Queue queue =
        (Queue)
            Proxy.newProxyInstance(
                JMSDecoratorGetDestinationTest.class.getClassLoader(),
                new Class<?>[] {Queue.class},
                (proxy, method, args) -> null);
    AtomicReference<MessageProducer> latched = new AtomicReference<>();
    MessageProducer latchedProducer =
        producer(
            new Class<?>[] {QueueSender.class},
            latchedCalls,
            () -> {
              throw unimplemented(latched.get());
            },
            queue);
    latched.set(latchedProducer);
    PRODUCER_DECORATE.getDestination(latchedProducer);

    // a differently-shaped proxy class (plain MessageProducer) still calls through normally
    AtomicInteger otherCalls = new AtomicInteger();
    MessageProducer other =
        producer(new Class<?>[] {MessageProducer.class}, otherCalls, () -> null, null);

    assertNull(PRODUCER_DECORATE.getDestination(other));
    assertEquals(1, otherCalls.get());
  }
}
