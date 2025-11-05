package datadog.trace.util.queue;

import datadog.environment.JavaVirtualMachine;
import org.jctools.queues.MpscArrayQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.jctools.queues.SpmcArrayQueue;
import org.jctools.queues.SpscArrayQueue;

public final class Queues {
  private static final boolean CAN_USE_VARHANDLES = JavaVirtualMachine.isJavaVersionAtLeast(9);

  private Queues() {}

  public static <E> NonBlockingQueue<E> mpscArrayQueue(int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new MpscArrayQueueVarHandle<>(requestedCapacity);
    }
    return new JctoolsWrappedQueue<>(new MpscArrayQueue<>(requestedCapacity));
  }

  public static <E> NonBlockingQueue<E> spmcArrayQueue(int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new SpmcArrayQueueVarHandle<>(requestedCapacity);
    }
    return new JctoolsWrappedQueue<>(new SpmcArrayQueue<>(requestedCapacity));
  }

  public static <E> BlockingConsumerNonBlockingQueue<E> mpscBlockingConsumerArrayQueue(
      int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new MpscBlockingConsumerArrayQueueVarHandle<>(requestedCapacity);
    }
    return new JctoolsMpscBlockingConsumerWrappedQueue<>(
        new MpscBlockingConsumerArrayQueue<>(requestedCapacity));
  }

  public static <E> NonBlockingQueue<E> spscArrayQueue(int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new SpscArrayQueueVarHandle<>(requestedCapacity);
    }
    return new JctoolsWrappedQueue<>(new SpscArrayQueue<>(requestedCapacity));
  }
}
