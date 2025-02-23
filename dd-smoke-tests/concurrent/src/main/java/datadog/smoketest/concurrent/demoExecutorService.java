package datadog.smoketest.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

// examples from https://www.baeldung.com/java-executor-service-tutorial
public class demoExecutorService {
  public static void main(String[] args) {
    System.out.println("=====demoExecutorService start=====");

    // instantiate executorService and result
    ExecutorService executorService = Executors.newFixedThreadPool(10);
    List<String> result = new ArrayList<>();

    // create callable task
    Callable<String> callableTask =
        () -> {
          TimeUnit.MILLISECONDS.sleep(300);
          return "callableTask executed!";
        };

    // invoke callable tasks three times
    List<Callable<String>> callableTasks = new ArrayList<>();
    callableTasks.add(callableTask);
    callableTasks.add(callableTask);
    callableTasks.add(callableTask);
    List<Future<String>> futures;
    try {
      futures = executorService.invokeAll(callableTasks);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    // add futures result to 'result' var
    for (Future<String> future : futures) {
      try {
        result.add(future.get());
      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      }
    }

    // shutdown executorService
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(800, TimeUnit.MILLISECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      executorService.shutdownNow();
    }

    // print result
    System.out.println("ExecutorService result: " + result);

    System.out.println("=====demoExecutorService finish=====");
  }
}
