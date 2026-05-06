/**
 * JFR event type definitions for the typed JafarParser API.
 *
 * <p>This package contains interfaces annotated with {@code @JfrType} that define the structure of
 * JFR events used for profiling. The typed parser generates implementations at runtime for
 * efficient event processing. Only fields actually used by the converter are declared - this allows
 * the parser to skip extraction of unused fields for better performance.
 *
 * <p>Event types:
 *
 * <ul>
 *   <li>{@link com.datadog.profiling.otel.jfr.ExecutionSample} - CPU profiling samples
 *       (datadog.ExecutionSample)
 *   <li>{@link com.datadog.profiling.otel.jfr.MethodSample} - Wall-clock profiling samples
 *       (datadog.MethodSample)
 *   <li>{@link com.datadog.profiling.otel.jfr.ObjectSample} - Allocation profiling samples
 *       (datadog.ObjectSample)
 *   <li>{@link com.datadog.profiling.otel.jfr.JavaMonitorEnter} - Lock contention events
 *       (jdk.JavaMonitorEnter)
 *   <li>{@link com.datadog.profiling.otel.jfr.JavaMonitorWait} - Monitor wait events
 *       (jdk.JavaMonitorWait)
 * </ul>
 *
 * <p>Supporting types for stack trace representation:
 *
 * <ul>
 *   <li>{@link com.datadog.profiling.otel.jfr.JfrStackTrace} - Stack trace (frames only)
 *   <li>{@link com.datadog.profiling.otel.jfr.JfrStackFrame} - Stack frame (method, line number)
 *   <li>{@link com.datadog.profiling.otel.jfr.JfrMethod} - Method (type, name)
 *   <li>{@link com.datadog.profiling.otel.jfr.JfrClass} - Class (name only)
 * </ul>
 */
package com.datadog.profiling.otel.jfr;
