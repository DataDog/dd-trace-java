package org.example;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class TestSucceedAndFieldInitResource {

  public static final AtomicInteger CONSTRUCTOR_INVOCATIONS = new AtomicInteger(0);

  public TestSucceedAndFieldInitResource() {
    CONSTRUCTOR_INVOCATIONS.incrementAndGet();
  }

  @Test
  public void test_skippable() {}

  @Test
  public void test_runs() {}
}
