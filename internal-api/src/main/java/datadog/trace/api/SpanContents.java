package datadog.trace.api;

/**
 * A span's contents, for tracer-internal readers and writers. Writes go straight to storage, with
 * no interception. Thread safety is up to the implementation.
 *
 * <p>{@link ReadableSpanContents} and {@link WritableSpanContents} are facets that say what a
 * caller may do, not a protection: callers handed one facet must not cast to the other.
 *
 * <p>This is the first slice: only what the eager tag post-processors need. Name and id variants,
 * primitive setters and typed getters come as more readers and writers move to it.
 */
public interface SpanContents extends ReadableSpanContents, WritableSpanContents {}
