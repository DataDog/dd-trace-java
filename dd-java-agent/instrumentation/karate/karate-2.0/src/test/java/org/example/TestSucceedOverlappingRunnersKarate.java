package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class TestSucceedOverlappingRunnersKarate {

  @Test
  public void test() throws Exception {
    Callable<Results> run =
        () -> Runner.path("classpath:org/example/test_overlapping_runners.feature").parallel(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Results> first = executor.submit(run);
      Future<Results> second = executor.submit(run);
      assertEquals(0, first.get(30, TimeUnit.SECONDS).getFailCount());
      assertEquals(0, second.get(30, TimeUnit.SECONDS).getFailCount());
    } finally {
      executor.shutdownNow();
    }
  }
}
