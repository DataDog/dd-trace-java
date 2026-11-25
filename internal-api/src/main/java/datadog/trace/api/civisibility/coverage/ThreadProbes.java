package datadog.trace.api.civisibility.coverage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The probe arrays of one thread, and the owner of the code the thread runs: the innermost window
 * open on the thread; with none open, the open window of the test whose context the thread carries;
 * else Shared. While a scope whose context carries another test is active on a thread with a window
 * open, the owner is that test's window, or Shared once that window has closed.
 *
 * <p>Only the thread stores into its arrays. A method takes its array at entry and keeps it until
 * it returns, and what an array holds belongs to the owner it was handed out to: a method's lines
 * go to the owner of the code when the method started, or to Shared once that owner's window has
 * reported. When the owner changes, the thread moves what its arrays hold to their owners and hands
 * out new arrays to the code that starts from then on. The arrays handed out before are read again
 * at later changes, for the methods still running with them. Such an array is dropped after {@value
 * #IDLE_CHANGES} changes without a store into it, or {@value #IDLE_CHANGES_AFTER_LATE_STORE} once a
 * method stored into it after a change; a method that stores into a dropped array loses those
 * lines.
 */
final class ThreadProbes {

  private static final int IDLE_CHANGES = 2;
  private static final int IDLE_CHANGES_AFTER_LATE_STORE = 64;

  /** Retired arrays beyond which a change of the owner for a scope also counts against them. */
  private static final int RETIRED_ARRAYS_KEPT_ACROSS_SCOPES = 256;

  final Thread thread;

  /**
   * The arrays handed out since the last change, by class id. The thread reads it without the lock:
   * an entry it reads after another thread cleared it is an array that is read again at the next
   * changes.
   */
  private boolean[][] table = new boolean[16][];

  private boolean primary;

  /** The owner of the code the thread runs, or null for Shared. Written under the lock. */
  @Nullable private volatile CoverageWindow owner;

  /** The innermost window open on the thread. Written under the lock. */
  @Nullable private volatile CoverageWindow innermost;

  /**
   * Whether the owner comes from the context of the active scope rather than from the innermost
   * window. Written under the lock.
   */
  private boolean ownerFromScope;

  // guarded by this: the arrays handed out since the last change
  private int[] handedClassIds = new int[16];
  private boolean[][] handedArrays = new boolean[16][];
  private int handedCount;

  // guarded by this: arrays handed out before the last change that can still be stored into
  private int[] retiredClassIds = new int[16];
  private boolean[][] retiredArrays = new boolean[16][];
  private CoverageWindow[] retiredOwners = new CoverageWindow[16];
  private int[] retiredIdleChanges = new int[16];
  private boolean[] retiredStoredLate = new boolean[16];
  private int retiredCount;

  ThreadProbes(Thread thread) {
    this.thread = thread;
  }

  /** The thread's array for a class. Called by the thread at method entry. */
  boolean[] take(int classId) {
    if (innermost == null) {
      CoverageWindow contextWindow = ProbeRecorder.contextWindow();
      if (contextWindow != owner) {
        followContext(contextWindow);
      }
    }
    boolean[][] handedOut = table;
    if (classId < handedOut.length) {
      boolean[] probes = handedOut[classId];
      if (probes != null) {
        return probes;
      }
    }
    return handOut(classId);
  }

  /** Allocates the thread's array for a class, if it has none since the last change. */
  private synchronized boolean[] handOut(int classId) {
    boolean[][] handedOut = table;
    if (classId >= handedOut.length) {
      handedOut = Arrays.copyOf(handedOut, Math.max(classId + 1, handedOut.length * 2));
      table = handedOut;
    }
    boolean[] probes = handedOut[classId];
    if (probes == null) {
      probes = ProbeRecorder.newProbes(classId);
      handedOut[classId] = probes;
      if (handedCount == handedArrays.length) {
        handedClassIds = Arrays.copyOf(handedClassIds, handedCount * 2);
        handedArrays = Arrays.copyOf(handedArrays, handedCount * 2);
      }
      handedClassIds[handedCount] = classId;
      handedArrays[handedCount++] = probes;
    }
    return probes;
  }

  /** Makes {@code primaryTable} the thread's table. Called by the thread. */
  synchronized void usePrimaryTable(boolean[][] primaryTable) {
    for (int i = 0; i < handedCount; i++) {
      primaryTable[handedClassIds[i]] = handedArrays[i];
    }
    table = primaryTable;
    primary = true;
    if (innermost != null) {
      ProbeRecorder.primaryThread = thread;
    }
  }

  boolean isPrimary() {
    return primary;
  }

  /** Whether a window is open on the thread. Called by the thread. */
  boolean hasOpenWindow() {
    return innermost != null;
  }

  /**
   * A scope became active on the thread, and its context carries the test whose coverage store is
   * {@code window}. While a window is open on the thread, the code the scope runs belongs to {@code
   * window} if it is open, and to Shared once it has closed. Called by the thread.
   */
  void scopeActivated(CoverageWindow window) {
    if (window == innermost && window == owner && !ownerFromScope) {
      return;
    }
    followScope(window);
  }

  private synchronized void followScope(CoverageWindow window) {
    CoverageWindow current = innermost;
    if (current == null) {
      // take() follows the context
      return;
    }
    CoverageWindow next;
    if (window == current) {
      next = current;
    } else if (window.addOtherThread(this)) {
      next = window;
    } else {
      // the test's window closed
      next = null;
    }
    if (next != owner) {
      changeScopeOwner(next);
    }
    ownerFromScope = next != current;
  }

  /**
   * The active scope of the thread closed: the innermost window owns the code again, until a scope
   * that becomes active says otherwise. Called by the thread.
   */
  void scopeClosed() {
    if (ownerFromScope) {
      endScopeOwner();
    }
  }

  private synchronized void endScopeOwner() {
    if (!ownerFromScope) {
      return;
    }
    ownerFromScope = false;
    CoverageWindow current = innermost;
    if (current != owner) {
      changeScopeOwner(current);
    }
  }

  private synchronized void followContext(@Nullable CoverageWindow window) {
    if (innermost != null || window == owner) {
      return;
    }
    if (window != null && !window.addOtherThread(this)) {
      // it closed meanwhile
      if (owner == null) {
        return;
      }
      window = null;
    }
    changeOwner(window);
  }

  /**
   * A window opens on this thread. Called by the thread.
   *
   * @return the window that was innermost
   */
  @Nullable
  synchronized CoverageWindow open(CoverageWindow window) {
    CoverageWindow outer = innermost;
    changeOwner(window);
    innermost = window;
    ownerFromScope = false;
    if (primary) {
      ProbeRecorder.primaryThread = thread;
    }
    return outer;
  }

  /**
   * The innermost window of this thread hands over to {@code window}: the code the thread runs from
   * now on belongs to {@code window}, and so does what it ran for {@code replaced}. Called by the
   * thread.
   *
   * @return {@code false} if {@code replaced} is not the innermost window
   */
  synchronized boolean replace(CoverageWindow replaced, CoverageWindow window) {
    if (innermost != replaced) {
      return false;
    }
    innermost = window;
    if (!ownerFromScope) {
      owner = window;
    }
    for (int i = 0; i < retiredCount; i++) {
      if (retiredOwners[i] == replaced) {
        retiredOwners[i] = window;
      }
    }
    return true;
  }

  /** A window that opened on this thread closes. */
  synchronized void close(CoverageWindow window) {
    if (innermost != window) {
      // closed out of order: the thread's owner does not change
      return;
    }
    CoverageWindow next = window.outer();
    while (next != null && next.isClosed()) {
      next = next.outer();
    }
    changeOwner(next);
    innermost = next;
    ownerFromScope = false;
    if (primary && next == null) {
      ProbeRecorder.primaryThread = null;
    }
  }

  /** A window that this thread ran code for through its test's context closes. */
  synchronized void leave(CoverageWindow window) {
    if (owner == window) {
      changeOwner(null);
    }
  }

  /** Moves to Shared what the thread hit, unless an open window owns its code. */
  synchronized void flushShared() {
    CoverageWindow current = owner;
    if (current == null || current.isClosed()) {
      changeOwner(null);
    } else {
      moveRetiredHits();
    }
  }

  /** Moves what the thread hit to its owners. Called once the thread has died. */
  synchronized void flushDead() {
    moveRetiredHits();
    moveHandedHits();
    // nothing runs on the thread any more
    Arrays.fill(retiredArrays, 0, retiredCount, null);
    Arrays.fill(retiredOwners, 0, retiredCount, null);
    retiredCount = 0;
  }

  /** Holds the lock. */
  private void changeOwner(@Nullable CoverageWindow next) {
    moveRetiredHits();
    moveHandedHits();
    owner = next;
  }

  /**
   * Changes the owner for the code a scope runs. Unlike other changes, it does not count against
   * the arrays handed out before, which the methods that run around the scope keep storing into,
   * unless many of them wait to be read again. Holds the lock.
   */
  private void changeScopeOwner(@Nullable CoverageWindow next) {
    if (retiredCount > RETIRED_ARRAYS_KEPT_ACROSS_SCOPES) {
      moveRetiredHits();
    }
    moveHandedHits();
    owner = next;
  }

  /** Moves what the arrays handed out before the last change hold. Holds the lock. */
  private void moveRetiredHits() {
    int kept = 0;
    for (int i = 0; i < retiredCount; i++) {
      int classId = retiredClassIds[i];
      boolean[] probes = retiredArrays[i];
      InstrumentedClass instrumentedClass = ProbeRecorder.CLASSES[classId];
      long[] hits = instrumentedClass.takeMethodHits(probes);
      if (hits != null) {
        CoverageWindow handedOutTo = retiredOwners[i];
        if (handedOutTo == null) {
          ProbeRecorder.addSharedHits(classId, hits);
        } else if (!handedOutTo.addHits(
            Collections.singletonList(
                new CoverageWindow.ClassHits(classId, instrumentedClass, hits)))) {
          // the window reported: what the array gets from now on is Shared's
          retiredOwners[i] = null;
        }
        retiredIdleChanges[i] = 0;
        retiredStoredLate[i] = true;
      } else {
        retiredIdleChanges[i]++;
      }
      int idleLimit = retiredStoredLate[i] ? IDLE_CHANGES_AFTER_LATE_STORE : IDLE_CHANGES;
      if (retiredIdleChanges[i] < idleLimit) {
        retiredClassIds[kept] = classId;
        retiredArrays[kept] = probes;
        retiredOwners[kept] = retiredOwners[i];
        retiredIdleChanges[kept] = retiredIdleChanges[i];
        retiredStoredLate[kept] = retiredStoredLate[i];
        kept++;
      }
    }
    Arrays.fill(retiredArrays, kept, retiredCount, null);
    Arrays.fill(retiredOwners, kept, retiredCount, null);
    retiredCount = kept;
  }

  /**
   * Moves what the arrays handed out since the last change hold to the owner, and makes code that
   * starts from now on take new arrays. Holds the lock.
   */
  private void moveHandedHits() {
    CoverageWindow target = owner;
    List<CoverageWindow.ClassHits> windowHits = null;
    boolean[][] handedOut = table;
    for (int i = 0; i < handedCount; i++) {
      int classId = handedClassIds[i];
      boolean[] probes = handedArrays[i];
      InstrumentedClass instrumentedClass = ProbeRecorder.CLASSES[classId];
      long[] hits = instrumentedClass.takeMethodHits(probes);
      if (hits != null) {
        if (target == null) {
          ProbeRecorder.addSharedHits(classId, hits);
        } else {
          if (windowHits == null) {
            windowHits = new ArrayList<>(handedCount - i);
          }
          windowHits.add(new CoverageWindow.ClassHits(classId, instrumentedClass, hits));
        }
      }
      if (handedOut[classId] == probes) {
        handedOut[classId] = null;
      }
      retire(classId, probes, target);
      handedArrays[i] = null;
    }
    handedCount = 0;
    if (windowHits != null) {
      target.addHits(windowHits);
    }
  }

  private void retire(int classId, boolean[] probes, @Nullable CoverageWindow handedOutTo) {
    if (retiredCount == retiredArrays.length) {
      int length = retiredCount * 2;
      retiredClassIds = Arrays.copyOf(retiredClassIds, length);
      retiredArrays = Arrays.copyOf(retiredArrays, length);
      retiredOwners = Arrays.copyOf(retiredOwners, length);
      retiredIdleChanges = Arrays.copyOf(retiredIdleChanges, length);
      retiredStoredLate = Arrays.copyOf(retiredStoredLate, length);
    }
    retiredClassIds[retiredCount] = classId;
    retiredArrays[retiredCount] = probes;
    retiredOwners[retiredCount] = handedOutTo;
    retiredIdleChanges[retiredCount] = 0;
    retiredStoredLate[retiredCount] = false;
    retiredCount++;
  }
}
