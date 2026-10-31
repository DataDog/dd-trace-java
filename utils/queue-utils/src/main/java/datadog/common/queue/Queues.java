package datadog.common.queue;

import datadog.environment.JavaVirtualMachine;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpmcArrayQueue;
import org.jctools.queues.MpscArrayQueue;
import org.jctools.queues.SpmcArrayQueue;
import org.jctools.queues.SpscArrayQueue;
import org.jctools.queues.varhandle.MpmcVarHandleArrayQueue;
import org.jctools.queues.varhandle.MpscVarHandleArrayQueue;
import org.jctools.queues.varhandle.SpmcVarHandleArrayQueue;
import org.jctools.queues.varhandle.SpscVarHandleArrayQueue;

/**
 * A utility class for creating various high-performance queue implementations used for inter-thread
 * communication. This class provides factory methods for creating non-blocking and
 * partially-blocking queues optimized for different producer-consumer configurations.
 *
 * <p>Depending on the Java runtime version, this class will choose the most efficient
 * implementation available:
 *
 * <ul>
 *   <li>For Java 25 and above, {@code VarHandle}-based queue implementations are used for improved
 *       performance and without relying on {@code sun.misc.Unsafe}.
 *   <li>For Java 8, {@code JCTools}-based wrappers are used instead.
 * </ul>
 *
 * <p>Use these factories for direct JCTools queue operations like {@code offer}, {@code poll}, or
 * {@code fill}. Use {@link WorkQueues} for cancellable reservations, batch capacity claims,
 * closeable queues, or processing with retry strategies.
 */
public final class Queues {

  private static final boolean CAN_USE_VARHANDLES = JavaVirtualMachine.isJavaVersionAtLeast(25);

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
      return new MpscVarHandleArrayQueue<>(requestedCapacity);
    }
    return new MpscArrayQueue<>(requestedCapacity);
  }

  /**
   * Creates a Multiple Producer, Multiple Consumer (MPMC) array-backed queue.
   *
   * <p>{@code offer} refuses when the ring's indexes say it is full, and a slot claimed by a
   * producer that has not finished publishing counts as occupied. A caller that tracks occupancy
   * itself, by counting completed offers and polls, will therefore see refusals while its own count
   * says there is room: measured at roughly 0.24% of offers with four producers and four consumers
   * on a queue of eight. {@link WorkQueues#createMpmcQueue} avoids this by claiming capacity before
   * it offers.
   *
   * @param requestedCapacity the requested capacity of the queue. Will be rounded to the next power
   *     of two, and is not permitted to be less than two.
   * @return a new {@link MessagePassingQueue} instance suitable for MPMC usage
   */
  public static <E> MessagePassingQueue<E> mpmcArrayQueue(int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new MpmcVarHandleArrayQueue<>(requestedCapacity);
    }
    return new MpmcArrayQueue<>(requestedCapacity);
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
      return new SpmcVarHandleArrayQueue<>(requestedCapacity);
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
      return new MpscBlockingConsumerVarHandleArrayQueue<>(requestedCapacity);
    }
    return new MpscBlockingConsumerArrayQueue<>(requestedCapacity);
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
      return new SpscVarHandleArrayQueue<>(requestedCapacity);
    }
    return new SpscArrayQueue<>(requestedCapacity);
  }
}
