package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.List;

/**
 * The correlated lifecycle of one scope continuation: its capture, every activation, and every
 * resolution (finish/cancel). Built incrementally as events arrive on different threads, so all
 * mutating access is synchronized on the instance.
 */
public final class ContinuationRecord {
  public final long seq;
  public final DDTraceId traceId;
  public final long spanId;
  public final byte source;

  /**
   * {@code true} when activation/resolution was seen without a preceding capture in this window.
   */
  public final boolean orphan;

  private ScopeEvent capture;
  private final List<ScopeEvent> activations = new ArrayList<>(1);
  private final List<ScopeEvent> resolutions = new ArrayList<>(1);

  ContinuationRecord(
      long seq, DDTraceId traceId, long spanId, byte source, boolean orphan, ScopeEvent capture) {
    this.seq = seq;
    this.traceId = traceId;
    this.spanId = spanId;
    this.source = source;
    this.orphan = orphan;
    this.capture = capture;
  }

  synchronized void addActivation(ScopeEvent event) {
    activations.add(event);
  }

  synchronized void addResolution(ScopeEvent event) {
    resolutions.add(event);
  }

  public synchronized ScopeEvent capture() {
    return capture;
  }

  public synchronized List<ScopeEvent> activations() {
    return new ArrayList<>(activations);
  }

  public synchronized List<ScopeEvent> resolutions() {
    return new ArrayList<>(resolutions);
  }

  public synchronized boolean isActivated() {
    return !activations.isEmpty();
  }

  public synchronized boolean isResolved() {
    return !resolutions.isEmpty();
  }

  /** Earliest known event time for ordering the timeline. */
  public synchronized long firstNanos() {
    long min = capture != null ? capture.nanos : Long.MAX_VALUE;
    for (ScopeEvent e : activations) {
      min = Math.min(min, e.nanos);
    }
    for (ScopeEvent e : resolutions) {
      min = Math.min(min, e.nanos);
    }
    return min;
  }

  public String sourceName() {
    return ScopeSources.name(source);
  }
}
