package gw.internal.xml.ws;

import datadog.trace.api.Trace;

/**
 * Negative control: a Thread subclass the matcher must ignore (not an {@code AsyncResponseImpl$…}).
 */
public class UnrelatedWorker extends Thread {

  @Override
  public void run() {
    unrelatedWork();
  }

  @Trace(operationName = "unrelated.work")
  static void unrelatedWork() {}
}
