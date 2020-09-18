package datadog.smoketest.springboot.controller;

import datadog.smoketest.springboot.AsyncTask;
import datadog.smoketest.springboot.grpc.AsynchronousGreeter;
import datadog.smoketest.springboot.grpc.SynchronousGreeter;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class WebController {

  private final ExecutorService pool = Executors.newFixedThreadPool(5);

  private final AsynchronousGreeter asyncGreeter;
  private final SynchronousGreeter greeter;
  private final AsyncTask asyncTask;

  public WebController(
      AsynchronousGreeter asyncGreeter, SynchronousGreeter greeter, AsyncTask asyncTask) {
    this.asyncGreeter = asyncGreeter;
    this.greeter = greeter;
    this.asyncTask = asyncTask;
  }

  @RequestMapping("/greeting")
  public String greeting() {
    return greeter.greet();
  }

  @RequestMapping("/async_greeting")
  public String asyncGreeting() {
    return asyncGreeter.greet();
  }

  @RequestMapping("/async_cf_greeting")
  public String asyncCompleteableFutureGreeting() {
    CompletableFuture<String>[] cfs = new CompletableFuture[20];
    for (int i = 0; i < cfs.length; ++i) {
      cfs[i] =
          CompletableFuture.supplyAsync(() -> "something", pool)
              .thenApplyAsync(x -> asyncGreeter.greet(), pool);
    }
    return CompletableFuture.allOf(cfs).thenApply(x -> "bye").join();
  }

  @RequestMapping("/async_concurrent_greeting")
  @SuppressWarnings("unchecked")
  public String asyncConcurrentGreeting() throws Exception {
    // more tasks than threads to force some parallel activity
    // onto the same threads
    Future[] futures = new Future[20];
    for (int i = 0; i < futures.length; ++i) {
      futures[i] =
          pool.submit(
              new Callable<String>() {
                @Override
                public String call() {
                  return asyncGreeter.greet();
                }
              });
    }
    String response = "";
    for (Future<String> f : futures) {
      response = f.get(30, TimeUnit.SECONDS);
    }
    return response;
  }

  @RequestMapping("async_annotation_greeting")
  public String asyncAnnotationGreeting() {
    return asyncTask.greet().join();
  }
}
