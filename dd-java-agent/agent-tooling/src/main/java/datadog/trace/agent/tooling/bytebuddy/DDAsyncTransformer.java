package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.util.AgentThreadFactory.AgentThread.ASYNC_TRANSFORMER;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides asynchronous class transformations on a separate agent thread.
 *
 * <p>Uses a lightweight exchange mechanism for swapping requests and results.
 */
final class DDAsyncTransformer implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(DDAsyncTransformer.class);

  private static final long TRANSFORM_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);

  interface TransformTask {
    byte[] doTransform(
        Object javaModule,
        ClassLoader classLoader,
        String internalClassName,
        ProtectionDomain protectionDomain,
        byte[] classFileBuffer)
        throws IllegalClassFormatException;
  }

  // first 16-bits represent slots available for use
  private static final int AVAILABLE_MASK = 0x0000FFFF;
  // last 16-bits represent slots ready for transforming
  private static final int READY_MASK = 0xFFFF0000;

  // maintain slot status (available/ready) in a single atomic variable
  private final AtomicInteger slotStates = new AtomicInteger(AVAILABLE_MASK);
  private final TransformExchanger[] exchangers = new TransformExchanger[16];

  private final ThreadLocal<TransformExchanger> localExchanger =
      new ThreadLocal<TransformExchanger>() {
        @Override
        protected TransformExchanger initialValue() {
          return new TransformExchanger();
        }
      };

  private final TransformTask transformTask;
  private final Thread transformThread;

  DDAsyncTransformer(TransformTask transformTask) {
    this.transformTask = transformTask;
    this.transformThread = newAgentThread(ASYNC_TRANSFORMER, this);
    this.transformThread.start();
  }

  public byte[] awaitTransform(
      Object javaModule,
      ClassLoader classLoader,
      String internalClassName,
      ProtectionDomain protectionDomain,
      byte[] classFileBuffer)
      throws IllegalClassFormatException {

    if (Thread.currentThread() != transformThread) { // secondary transformations cannot be async
      int slot = acquireSlot();
      if (slot >= 0) {
        log.info("--------  await {} for {} slot {}", internalClassName, classLoader, slot);
        TransformExchanger exchanger = localExchanger.get();
        exchanger.javaModule = javaModule;
        exchanger.classLoader = classLoader;
        exchanger.internalClassName = internalClassName;
        exchanger.protectionDomain = protectionDomain;
        exchanger.classFileBuffer = classFileBuffer;
        exchanger.tccl = Thread.currentThread().getContextClassLoader();
        exchangers[slot] = exchanger;
        submitRequest(slot); // compare-and-swap makes these changes visible to transform thread
        LockSupport.unpark(transformThread);
        try {
          if (exchanger.tryAcquire(TRANSFORM_TIMEOUT_NANOS, TimeUnit.NANOSECONDS)) {
            return exchanger.classFileBuffer;
          }
          // fall-through to synchronous transformation
        } catch (Throwable e) {
          // fall-through to synchronous transformation
        } finally {
          exchanger.classFileBuffer = null;
        }
        if (cancelRequest(slot)) { // did we cancel request before transform thread took it?
          exchanger.javaModule = null;
          exchanger.classLoader = null;
          exchanger.internalClassName = null;
          exchanger.protectionDomain = null;
          exchanger.tccl = null;
          exchangers[slot] = null;
          recycleSlot(slot);
        } else {
          localExchanger.remove(); // canceled too late, can't re-use this exchanger
        }
      }
    }

    // synchronous transformation...
    return transformTask.doTransform(
        javaModule, classLoader, internalClassName, protectionDomain, classFileBuffer);
  }

  @Override
  public void run() {
    while (true) {
      int slot = acceptRequest();
      if (slot >= 0) {
        TransformExchanger exchanger = exchangers[slot];
        log.info(
            "--------  transform {} for {} slot {}",
            exchanger.internalClassName,
            exchanger.classLoader,
            slot);
        try {
          transformThread.setContextClassLoader(exchanger.tccl);
          exchanger.classFileBuffer = // use same exchanger to return transformed bytes
              transformTask.doTransform(
                  exchanger.javaModule,
                  exchanger.classLoader,
                  exchanger.internalClassName,
                  exchanger.protectionDomain,
                  exchanger.classFileBuffer);
        } catch (Throwable e) {
          exchanger.classFileBuffer = null;
          log.warn("Async transformation failed for {}", exchanger.internalClassName, e);
        } finally {
          exchanger.release(); // semaphore call makes result visible to request thread
          transformThread.setContextClassLoader(null);
          exchanger.javaModule = null;
          exchanger.classLoader = null;
          exchanger.internalClassName = null;
          exchanger.protectionDomain = null;
          exchanger.tccl = null;
          exchangers[slot] = null;
          recycleSlot(slot);
        }
      } else {
        LockSupport.park(); // suspend thread until next request comes in
      }
    }
  }

  private int acquireSlot() {
    int available;
    int currentStates = slotStates.get();
    while ((available = Integer.lowestOneBit(currentStates & AVAILABLE_MASK)) != 0) {
      if (slotStates.compareAndSet(currentStates, currentStates & ~available)) {
        return Integer.numberOfTrailingZeros(available);
      }
      currentStates = slotStates.get();
    }
    return -1;
  }

  private void submitRequest(int slot) {
    int ready = 1 << (slot + 16);
    int currentStates = slotStates.get();
    while (!slotStates.compareAndSet(currentStates, currentStates | ready)) {
      currentStates = slotStates.get();
    }
  }

  private int acceptRequest() {
    int ready;
    int currentStates = slotStates.get();
    while ((ready = Integer.lowestOneBit(currentStates & READY_MASK)) != 0) {
      if (slotStates.compareAndSet(currentStates, currentStates & ~ready)) {
        return Integer.numberOfTrailingZeros(ready >>> 16);
      }
      currentStates = slotStates.get();
    }
    return -1;
  }

  private boolean cancelRequest(int slot) {
    int ready = 1 << (slot + 16);
    int currentStates = slotStates.get();
    while ((currentStates & ready) != 0) {
      if (slotStates.compareAndSet(currentStates, currentStates & ~ready)) {
        return true;
      }
      currentStates = slotStates.get();
    }
    return false;
  }

  private void recycleSlot(int slot) {
    int available = 1 << slot;
    int currentStates = slotStates.get();
    while (!slotStates.compareAndSet(currentStates, currentStates | available)) {
      currentStates = slotStates.get();
    }
  }

  static final class TransformExchanger extends Semaphore {
    TransformExchanger() {
      super(0);
    }

    Object javaModule;
    ClassLoader classLoader;
    String internalClassName;
    ProtectionDomain protectionDomain;
    byte[] classFileBuffer;
    ClassLoader tccl;
  }
}
