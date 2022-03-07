package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.util.AgentThreadFactory.AgentThread.ASYNC_MATCHER;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-writes a sequence of matchers, so they can be evaluated asynchronously.
 *
 * <p>The initial matcher in the sequence triggers evaluation of all the original matchers in a
 * separate thread. Subsequent matchers in the sequence then use the results of that asynchronous
 * matching.
 *
 * <p>This assumes that the initial matcher is always called first for a given set of parameters.
 */
public class AsyncMatching implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(AsyncMatching.class);

  private static final long MATCHING_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);

  // first 16-bits represent slots available for use
  private static final int AVAILABLE_MASK = 0x0000FFFF;
  // last 16-bits represent slots ready for matching
  private static final int READY_MASK = 0xFFFF0000;

  // maintain slot status (available/ready) in a single atomic variable
  private final AtomicInteger slotStates = new AtomicInteger(AVAILABLE_MASK);
  private final MatchingTask[] exchangers = new MatchingTask[16];

  private final List<AgentBuilder.RawMatcher> matchers = new ArrayList<>();
  private final ThreadLocal<MatchingTask> localTask =
      new ThreadLocal<MatchingTask>() {
        @Override
        protected MatchingTask initialValue() {
          return new MatchingTask();
        }
      };

  private final Thread matchingThread;

  public AsyncMatching() {
    matchingThread = newAgentThread(ASYNC_MATCHER, this);
    matchingThread.start();
  }

  public AgentBuilder.RawMatcher makeAsync(AgentBuilder.RawMatcher matcher) {
    int index = matchers.size();
    matchers.add(matcher);
    return 0 == index ? new TriggerMatch() : new RetrieveResult(index);
  }

  private class TriggerMatch implements AgentBuilder.RawMatcher {
    @Override
    public boolean matches(
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain) {

      MatchingTask matchingTask = localTask.get();
      matchingTask.typeDescription = typeDescription;
      matchingTask.classLoader = classLoader;
      matchingTask.module = module;
      matchingTask.classBeingRedefined = classBeingRedefined;
      matchingTask.protectionDomain = protectionDomain;
      matchingTask.tccl = Thread.currentThread().getContextClassLoader();

      if (Thread.currentThread() != matchingThread) { // avoid making recursive async requests
        int slot = acquireSlot();
        if (slot >= 0) {
          exchangers[slot] = matchingTask;
          submitRequest(slot); // compare-and-swap makes our task visible to matching thread
          LockSupport.unpark(matchingThread);
          try {
            if (matchingTask.tryAcquire(MATCHING_TIMEOUT_NANOS, TimeUnit.NANOSECONDS)) {
              return matchingTask.matches.get(0);
            }
          } catch (Throwable e) {
            // ignore...
          }
          if (cancelRequest(slot)) { // task was not accepted, matching thread may be stuck
            exchangers[slot] = null;
            recycleSlot(slot);
            // fall-through and use synchronous matching
          } else {
            log.debug("Async matching appears stuck for {}", typeDescription);
            localTask.remove(); // our task may be stuck, create a new task for next request
            return false; // assume no match rather than risk synchronous match getting stuck
          }
        }
      }

      // revert to synchronous matching if we were unable to make an asynchronous request
      matchingTask.run();
      matchingTask.typeDescription = null;
      matchingTask.classLoader = null;
      matchingTask.module = null;
      matchingTask.classBeingRedefined = null;
      matchingTask.protectionDomain = null;
      matchingTask.tccl = null;
      return matchingTask.matches.get(0);
    }
  }

  private class RetrieveResult implements AgentBuilder.RawMatcher {
    private final int index;

    RetrieveResult(int index) {
      this.index = index;
    }

    @Override
    public boolean matches(
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain) {
      return localTask.get().matches.get(index);
    }
  }

  private class MatchingTask extends Semaphore implements Runnable {
    final BitSet matches = new BitSet();

    TypeDescription typeDescription;
    ClassLoader classLoader;
    JavaModule module;
    Class<?> classBeingRedefined;
    ProtectionDomain protectionDomain;
    ClassLoader tccl;

    MatchingTask() {
      super(0);
    }

    @Override
    public void run() {
      matches.clear();
      for (int i = 0, size = matchers.size(); i < size; i++) {
        if (matchers
            .get(i)
            .matches(typeDescription, classLoader, module, classBeingRedefined, protectionDomain)) {
          matches.set(i);
        }
      }
    }
  }

  @Override
  public void run() {
    Thread currentThread = Thread.currentThread();
    while (true) {
      int slot = acceptRequest();
      if (slot >= 0) {
        MatchingTask matchingTask = exchangers[slot];
        currentThread.setContextClassLoader(matchingTask.tccl);
        try {
          matchingTask.run();
        } catch (Throwable e) {
          log.debug("Async matching failed for {}", matchingTask.typeDescription, e);
        } finally {
          currentThread.setContextClassLoader(null);
          matchingTask.typeDescription = null;
          matchingTask.classLoader = null;
          matchingTask.module = null;
          matchingTask.classBeingRedefined = null;
          matchingTask.protectionDomain = null;
          matchingTask.tccl = null;
          matchingTask.release(); // semaphore call makes result visible to request thread
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
}
