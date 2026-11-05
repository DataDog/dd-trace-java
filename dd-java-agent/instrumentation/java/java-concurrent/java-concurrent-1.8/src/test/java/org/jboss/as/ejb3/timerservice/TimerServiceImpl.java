package org.jboss.as.ejb3.timerservice;

import java.util.Timer;
import java.util.TimerTask;

/** Models WildFly calendar timers scheduling each timeout as a one-shot task. */
public class TimerServiceImpl {
  public void scheduleTimeout(Timer timer, TimerTask task) {
    timer.schedule(task, 10);
  }
}
