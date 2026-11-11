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
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Results> first = executor.submit(run("first"));
      Future<Results> second = executor.submit(run("second"));
      assertEquals(0, first.get(30, TimeUnit.SECONDS).getFailCount());
      assertEquals(0, second.get(30, TimeUnit.SECONDS).getFailCount());
    } finally {
      executor.shutdownNow();
    }
  }

  // Karate's report writing is not safe for concurrent runs sharing a report directory
  private static Callable<Results> run(String name) {
    return () ->
        Runner.path("classpath:org/example/test_overlapping_runners.feature")
            .reportDir("build/karate-reports/overlapping-" + name)
            .backupReportDir(false)
            .outputHtmlReport(false)
            .parallel(1);
  }
}
