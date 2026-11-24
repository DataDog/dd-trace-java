package org.example;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

public class OverlapBarrier {

  private static final CyclicBarrier BARRIER = new CyclicBarrier(2);

  public static void await() throws Exception {
    BARRIER.await(10, TimeUnit.SECONDS);
  }
}
