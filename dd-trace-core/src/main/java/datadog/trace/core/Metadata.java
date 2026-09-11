package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static java.util.Collections.emptyList;

import datadog.trace.api.TagMap;
import datadog.trace.api.cache.RadixTreeCache;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.List;
import java.util.Map;

public final class Metadata {
  private final long threadId;
  private final UTF8BytesString threadName;
  private final int httpStatusCode;
  private final TagMap tags;
  private final Map<String, String> baggage;

  private final int samplingPriority;
  private final boolean measured;
  private final boolean topLevel;
  private final CharSequence origin;
  private final int longRunningVersion;
  private final UTF8BytesString processTags;
  private final List<? extends AgentSpanLink> spanLinks;

  public Metadata(
      long threadId,
      UTF8BytesString threadName,
      TagMap tags,
      Map<String, String> baggage,
      int samplingPriority,
      boolean measured,
      boolean topLevel,
      int httpStatusCode,
      CharSequence origin,
      int longRunningVersion,
      UTF8BytesString processTags,
      List<? extends AgentSpanLink> spanLinks) {
    this.threadId = threadId;
    this.threadName = threadName;
    this.httpStatusCode = httpStatusCode;
    this.tags = tags;
    this.baggage = baggage;
    this.samplingPriority = samplingPriority;
    this.measured = measured;
    this.topLevel = topLevel;
    this.origin = origin;
    this.longRunningVersion = longRunningVersion;
    this.processTags = processTags;
    this.spanLinks = spanLinks == null ? emptyList() : spanLinks;
  }

  /**
   * The intercepted HTTP status, or {@link RadixTreeCache#UNSET_STATUS} when the span carries none.
   *
   * <p>Held as an int rather than as its rendering, so a serializer that encodes the status
   * numerically -- OTLP, whose semantic conventions type it as an integer -- never pays for a
   * string it will not send, and a serializer that needs the string asks for it explicitly.
   */
  public int getHttpStatusCode() {
    return httpStatusCode;
  }

  /**
   * The intercepted HTTP status rendered for the string-typed protocols (the Datadog msgpack
   * payloads and the CI Visibility intake), or null when the span carries none.
   *
   * <p>Backed by {@link RadixTreeCache#HTTP_STATUSES}, so a repeated status costs a lookup rather
   * than an allocation. Call it once per span and hold the result: nothing memoizes it here, since
   * a Metadata is consumed by exactly one serializer.
   */
  public UTF8BytesString getHttpStatusCodeString() {
    return httpStatusCode == RadixTreeCache.UNSET_STATUS
        ? null
        : RadixTreeCache.HTTP_STATUSES.get(httpStatusCode);
  }

  public CharSequence getOrigin() {
    return origin;
  }

  public long getThreadId() {
    return threadId;
  }

  public UTF8BytesString getThreadName() {
    return threadName;
  }

  public TagMap getTags() {
    return this.tags;
  }

  public Map<String, String> getBaggage() {
    return baggage;
  }

  public boolean measured() {
    return measured;
  }

  public int longRunningVersion() {
    return longRunningVersion;
  }

  public boolean topLevel() {
    return topLevel;
  }

  public boolean hasSamplingPriority() {
    return samplingPriority != UNSET;
  }

  public int samplingPriority() {
    return samplingPriority;
  }

  public UTF8BytesString processTags() {
    return processTags;
  }

  public List<? extends AgentSpanLink> getSpanLinks() {
    return spanLinks;
  }
}
