package datadog.trace.bootstrap.instrumentation.java.concurrent;

/** Marks agent callbacks that do not need Scala promise context propagation. */
public interface SkipScalaPromiseContextCapture {}
