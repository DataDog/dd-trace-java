package datadog.trace.bootstrap.instrumentation.api;

/**
 * Marker interface used to identify helpers that should be eagerly initialized when injected.
 *
 * <p>Eager helpers must declare a public static "init" method that takes no arguments.
 */
public interface EagerHelper {}
