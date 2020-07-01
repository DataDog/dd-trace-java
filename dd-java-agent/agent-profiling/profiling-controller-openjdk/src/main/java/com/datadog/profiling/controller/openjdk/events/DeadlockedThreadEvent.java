package com.datadog.profiling.controller.openjdk.events;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("datadog.DeadlockedThread")
@Label("Deadlocked Thread")
@Description("Datadog deadlock detection event - thread details.")
@Category("Datadog")
@Enabled
public class DeadlockedThreadEvent extends Event {
  @Label("Deadlocked Thread ID")
  private final long threadId;

  @Label("Deadlocked Thread Name")
  private final String threadName;

  @Label("Lock name")
  private final String lockName;

  @Label("Lock Owner Thread Name")
  private final String lockOwnerThreadName;

  @Label("Lock Owner Thread ID")
  private final long lockOwnerThreadId;

  @Label("Locking Frame")
  @Description(
      "Textual representation of the frame locking the monitor. 'null' for java.util.concurrent locks.")
  private final String lockingFrame;

  @Label("Waiting Frame")
  @Description(
      "Textual representation of the frame awaiting to lock a monitor or java.util.concurrent lock.")
  private final String waitingFrame;

  @Label("Deadlock ID")
  @Description("Referential index for data related to a particular deadlock")
  private final long id;

  DeadlockedThreadEvent() {
    this.id = Long.MIN_VALUE;
    this.threadId = Long.MIN_VALUE;
    this.threadName = null;
    this.lockOwnerThreadId = Long.MIN_VALUE;
    this.lockOwnerThreadName = null;
    this.lockName = null;
    this.lockingFrame = null;
    this.waitingFrame = null;
  }

  public DeadlockedThreadEvent(
      long id,
      long threadId,
      String threadName,
      long lockOwnerThreadId,
      String lockOwnerThreadName,
      String lockName,
      String lockingFrame,
      String waitingFrame) {
    this.id = id;
    this.threadId = threadId;
    this.threadName = threadName;
    this.lockOwnerThreadId = lockOwnerThreadId;
    this.lockOwnerThreadName = lockOwnerThreadName;
    this.lockName = lockName;
    this.lockingFrame = lockingFrame;
    this.waitingFrame = waitingFrame;
  }

  String getThreadName() {
    return threadName;
  }

  String getLockOwnerThreadName() {
    return lockOwnerThreadName;
  }

  String getLockingFrame() {
    return lockingFrame;
  }

  String getWaitingFrame() {
    return waitingFrame;
  }

  long getId() {
    return id;
  }
}
