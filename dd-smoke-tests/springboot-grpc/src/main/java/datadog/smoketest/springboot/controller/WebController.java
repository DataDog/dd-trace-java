package datadog.smoketest.springboot.controller;

import datadog.smoketest.springboot.AsyncTask;
import datadog.smoketest.springboot.grpc.AsynchronousGreeter;
import datadog.smoketest.springboot.spanner.SpannerTask;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebController {

  private final ExecutorService pool = Executors.newFixedThreadPool(5);

  private final AsynchronousGreeter asyncGreeter;
  private final AsyncTask asyncTask;
  private final SpannerTask spannerTask;

  public WebController(
      AsynchronousGreeter asyncGreeter, AsyncTask asyncTask, SpannerTask spannerTask) {
    this.asyncGreeter = asyncGreeter;
    this.asyncTask = asyncTask;
    this.spannerTask = spannerTask;
  }

  @RequestMapping("/spanner")
  public String spanner() {
    spannerTask.spannerResultSet().thenAccept(results -> {}).join();
    return "bye";
  }

  @RequestMapping("/async_cf_greeting")
  public String asyncCompleteableFutureGreeting(
      @RequestParam(value = "message", defaultValue = "aGVsbG8=" /*hello*/) final String message) {
    final String decodedMsg = decodeBase64(message);
    CompletableFuture<String>[] cfs = new CompletableFuture[20];
    for (int i = 0; i < cfs.length; ++i) {
      cfs[i] =
          CompletableFuture.supplyAsync(() -> "something", pool)
              .thenApplyAsync(x -> asyncGreeter.greet(decodedMsg), pool);
    }
    return CompletableFuture.allOf(cfs).thenApply(x -> "bye").join();
  }

  @RequestMapping("/async_concurrent_greeting")
  @SuppressWarnings("unchecked")
  public String asyncConcurrentGreeting(
      @RequestParam(value = "message", defaultValue = "aGVsbG8=") final String message)
      throws Exception {
    // more tasks than threads to force some parallel activity
    // onto the same threads
    Future[] futures = new Future[20];
    for (int i = 0; i < futures.length; ++i) {
      futures[i] = pool.submit(() -> asyncGreeter.greet(decodeBase64(message)));
    }
    String response = "";
    for (Future<String> f : futures) {
      response = f.get(30, TimeUnit.SECONDS);
    }
    return response;
  }

  @RequestMapping("async_annotation_greeting")
  public String asyncAnnotationGreeting(
      @RequestParam(value = "message", defaultValue = "aGVsbG8=") final String message) {
    return asyncTask.greet(decodeBase64(message)).join();
  }

  private static String decodeBase64(String str) {
    return new String(Base64.getDecoder().decode(str), StandardCharsets.US_ASCII);
  }
}
