package datadog.trace.util.queue;

import datadog.environment.JavaVirtualMachine;

public final class Queues {
  private static final boolean CAN_USE_VARHANDLES = JavaVirtualMachine.isJavaVersionAtLeast(9);

  private Queues() {}

  public static <E> BaseQueue<E> mpscArrayQueue(int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new MpscArrayQueueVarHandle<>(requestedCapacity);
    }
    return new MpscArrayQueue<>(requestedCapacity);
  }

  public static <E> BaseQueue<E> spmcArrayQueue(int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new SpmcArrayQueueVarHandle<>(requestedCapacity);
    }
    return new SpmcArrayQueue<>(requestedCapacity);
  }

  public static <E> BaseQueue<E> mpscBlockingConsumerArrayQueue(int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new MpscBlockingConsumerArrayQueueVarHandle<>(requestedCapacity);
    }
    return new MpscBlockingConsumerArrayQueue<>(requestedCapacity);
  }

  public static <E> BaseQueue<E> spscArrayQueue(int requestedCapacity) {
    if (CAN_USE_VARHANDLES) {
      return new SpscArrayQueueVarHandle<>(requestedCapacity);
    }
    return new SpscArrayQueue<>(requestedCapacity);
  }
}
