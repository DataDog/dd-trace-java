package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static java.util.Collections.emptyList;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.List;
import java.util.Map;

public final class Metadata {
  /** Serialized key for the HTTP status code in Datadog convention. */
  public static final UTF8BytesString HTTP_STATUS_KEY = UTF8BytesString.create(Tags.HTTP_STATUS);

  /** Serialized key for the HTTP status code in OpenTelemetry convention. */
  public static final UTF8BytesString HTTP_RESPONSE_STATUS_CODE_KEY =
      UTF8BytesString.create(Tags.HTTP_RESPONSE_STATUS_CODE);

  private final long threadId;
  private final UTF8BytesString threadName;
  private final UTF8BytesString httpStatusCode;
  private final UTF8BytesString httpStatusKey;
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
      UTF8BytesString httpStatusCode,
      CharSequence origin,
      int longRunningVersion,
      UTF8BytesString processTags,
      List<? extends AgentSpanLink> spanLinks) {
    this(
        threadId,
        threadName,
        tags,
        baggage,
        samplingPriority,
        measured,
        topLevel,
        httpStatusCode,
        HTTP_STATUS_KEY,
        origin,
        longRunningVersion,
        processTags,
        spanLinks);
  }

  public Metadata(
      long threadId,
      UTF8BytesString threadName,
      TagMap tags,
      Map<String, String> baggage,
      int samplingPriority,
      boolean measured,
      boolean topLevel,
      UTF8BytesString httpStatusCode,
      UTF8BytesString httpStatusKey,
      CharSequence origin,
      int longRunningVersion,
      UTF8BytesString processTags,
      List<? extends AgentSpanLink> spanLinks) {
    this.threadId = threadId;
    this.threadName = threadName;
    this.httpStatusCode = httpStatusCode;
    this.httpStatusKey = httpStatusKey;
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

  public UTF8BytesString getHttpStatusCode() {
    return httpStatusCode;
  }

  /** The serialized attribute key to use for the HTTP status code (DD vs OTel convention). */
  public UTF8BytesString getHttpStatusKey() {
    return httpStatusKey;
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
