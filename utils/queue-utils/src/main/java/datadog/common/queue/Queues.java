package datadog.common.queue;

import datadog.environment.JavaVirtualMachine;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscArrayQueue;
import org.jctools.queues.SpmcArrayQueue;
import org.jctools.queues.SpscArrayQueue;

/**
 * A utility class for creating various high-performance queue implementations used for inter-thread
 * communication. This class provides factory methods for creating non-blocking and
 * partially-blocking queues optimized for different producer-consumer configurations.
 *
 * <p>Depending on the Java runtime version, this class will choose the most efficient
 * implementation available:
 *
 * <ul>
 *   <li>For Java 9 and above, {@code VarHandle}-based queue implementations are used for improved
 *       performance and without relying on {@code sun.misc.Unsafe}.
 *   <li>For Java 8, {@code JCTools}-based wrappers are used instead.
 * </ul>
 */
public final class Queues {

  private static final boolean CAN_USE_VARHANDLES = JavaVirtualMachine.isJavaVersionAtLeast(9);

  private Queues() {}

  /**
   * Creates a Multiple Producer, Single Consumer (MPSC) array-backed queue.
   *
   * @param requestedCapacity the requested capacity of the queue. Will be rounded to the next power
   *     of two.
   * @return a new {@link MessagePassingQueue} instance suitable for MPSC usage
   */
  public static <E> MessagePassingQueue<E> mpscArrayQueue(int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new MpscArrayQueueVarHandle<>(requestedCapacity);
    }
    return new MpscArrayQueue<>(requestedCapacity);
  }

  /**
   * Creates a Single Producer, Multiple Consumer (SPMC) array-backed queue.
   *
   * <p>\ * @param requestedCapacity the requested capacity of the queue. Will be rounded to the
   * next power of two.
   *
   * @return a new {@link MessagePassingQueue} instance suitable for SPMC usage
   */
  public static <E> MessagePassingQueue<E> spmcArrayQueue(int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new SpmcArrayQueueVarHandle<>(requestedCapacity);
    } else {
      return new SpmcArrayQueue<>(requestedCapacity);
    }
  }

  /**
   * Creates a Multiple Producer, Single Consumer (MPSC) array-backed queue that allows blocking
   * behavior for the consumer.
   *
   * @param requestedCapacity the requested capacity of the queue. Will be rounded to the next power
   *     of two.
   * @return a new {@link MessagePassingBlockingQueue} instance suitable for MPSC usage with
   *     blocking consumption
   */
  public static <E> MessagePassingBlockingQueue<E> mpscBlockingConsumerArrayQueue(
      int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new MpscBlockingConsumerArrayQueueVarHandle<>(requestedCapacity);
    }
    return new MpscBlockingConsumerArrayQueueDelegate<>(requestedCapacity);
  }

  /**
   * Creates a Single Producer, Single Consumer (SPSC) array-backed queue.
   *
   * @param requestedCapacity the requested capacity of the queue. Will be rounded to the next power
   *     of two.
   * @return a new {@link MessagePassingQueue} instance suitable for SPSC usage
   */
  public static <E> MessagePassingQueue<E> spscArrayQueue(int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new SpscArrayQueueVarHandle<>(requestedCapacity);
    }
    return new SpscArrayQueue<>(requestedCapacity);
  }
}
