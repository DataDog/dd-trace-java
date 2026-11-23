package datadog.trace.bootstrap.instrumentation.java.concurrent;

/** Marks agent callbacks that do not need CompletableFuture context propagation. */
public interface SkipCompletableFutureContextCapture {}
