package org.example;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.concurrent.CountDownLatch;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

public class TestSucceedConcurrentRunners {

  public static CountDownLatch firstStarted;
  public static CountDownLatch secondStarted;
  public static CountDownLatch firstFinished;

  public static class First {
    @Test
    public void test_succeed() throws InterruptedException {
      firstStarted.countDown();
      secondStarted.await(10, SECONDS);
    }
  }

  @FixMethodOrder(MethodSorters.NAME_ASCENDING)
  public static class Second {
    @Test
    public void test_a_outlives_first() throws InterruptedException {
      secondStarted.countDown();
      firstFinished.await(10, SECONDS);
    }

    @Test
    public void test_b_after_first() {}
  }
}
