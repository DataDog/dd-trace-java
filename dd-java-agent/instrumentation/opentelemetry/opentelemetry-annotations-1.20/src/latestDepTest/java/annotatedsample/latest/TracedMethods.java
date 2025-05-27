package annotatedsample.latest;

import static datadog.trace.api.DDTags.SERVICE_NAME;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static io.opentelemetry.api.trace.SpanKind.CLIENT;
import static io.opentelemetry.api.trace.SpanKind.CONSUMER;
import static io.opentelemetry.api.trace.SpanKind.INTERNAL;
import static io.opentelemetry.api.trace.SpanKind.PRODUCER;
import static io.opentelemetry.api.trace.SpanKind.SERVER;
import static java.util.concurrent.TimeUnit.SECONDS;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public class TracedMethods {
  @WithSpan
  public static String sayHello() {
    activeSpan().setTag(SERVICE_NAME, "custom-service-name");
    return "hello!";
  }

  @WithSpan(value = "custom-operation-name")
  public static String sayHelloWithCustomOperationName() {
    activeSpan().setTag(SERVICE_NAME, "custom-service-name");
    return "hello!";
  }

  @WithSpan(kind = SERVER)
  public static String sayHelloWithServerKind() {
    return "hello!";
  }

  @WithSpan(kind = CLIENT)
  public static String sayHelloWithClientKind() {
    return "hello!";
  }

  @WithSpan(kind = PRODUCER)
  public static String sayHelloWithProducerKind() {
    return "hello!";
  }

  @WithSpan(kind = CONSUMER)
  public static String sayHelloWithConsumerKind() {
    return "hello!";
  }

  @WithSpan(kind = INTERNAL)
  public static String sayHelloWithInternalKind() {
    return "hello!";
  }

  @WithSpan(inheritContext = false)
  public static String sayHelloWithInANewTrace() {
    return "hello!";
  }

  @WithSpan
  public static String sayHelloWithStringAttribute(@SpanAttribute("custom-tag") String param) {
    return "hello!";
  }

  @WithSpan
  public static String sayHelloWithIntAttribute(@SpanAttribute("custom-tag") int param) {
    return "hello!";
  }

  @WithSpan
  public static String sayHelloWithLongAttribute(@SpanAttribute("custom-tag") long param) {
    return "hello!";
  }

  @WithSpan
  public static String sayHelloWithListAttribute(@SpanAttribute("custom-tag") List<?> param) {
    return "hello!";
  }

  @WithSpan
  public static void throwException() {
    throw new RuntimeException("Some exception");
  }

  public static String traceAnonymousInnerClass() {
    return new Callable<String>() {
      @WithSpan
      @Override
      public String call() {
        return "hello!";
      }
    }.call();
  }

  @WithSpan
  public static CompletableFuture<String> traceAsyncCompletableFuture(CountDownLatch latch) {
    return CompletableFuture.supplyAsync(
        () -> {
          await(latch);
          return "hello!";
        });
  }

  @WithSpan
  public static CompletableFuture<String> traceAsyncFailingCompletableFuture(
      CountDownLatch latch, RuntimeException exception) {
    return CompletableFuture.supplyAsync(
        () -> {
          await(latch);
          throw exception;
        });
  }

  @WithSpan
  public static CompletionStage<String> traceAsyncCompletionStage(CountDownLatch latch) {
    return CompletableFuture.supplyAsync(
        () -> {
          await(latch);
          return "hello!";
        });
  }

  @WithSpan
  public static CompletionStage<String> traceAsyncFailingCompletionStage(
      CountDownLatch latch, RuntimeException exception) {
    return CompletableFuture.supplyAsync(
        () -> {
          await(latch);
          throw exception;
        });
  }

  @WithSpan
  public static String sayHelloMeasured() {
    return "hello!";
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, SECONDS)) {
        throw new IllegalStateException("Latch still locked");
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
