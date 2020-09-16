package datadog.smoketest.springboot;

import datadog.smoketest.springboot.grpc.AsynchronousGreeter;
import org.springframework.scheduling.annotation.Async;

import java.util.concurrent.CompletableFuture;

public class AsyncTask {

  private final AsynchronousGreeter greeter;

  public AsyncTask(AsynchronousGreeter greeter) {
    this.greeter = greeter;
  }

  @Async
  public CompletableFuture<String> greet() {
    return CompletableFuture.completedFuture(greeter.greet());
  }
}
