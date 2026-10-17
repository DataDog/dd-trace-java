package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.function.Strategy;

/**
 * Extracts tags from a foreign object we do not own (a framework {@code Connection}, request,
 * response, etc.) onto a span — the extrinsic counterpart to {@link TagContributor}. This is the
 * irreducible "reach into version-specific framework objects" that cannot be expressed as data; it
 * stays imperative, but contained in a narrow, single-purpose place.
 *
 * <p>Two usage modes, chosen by the source's lifecycle vs. the span:
 *
 * <ul>
 *   <li>read {@code source} and place tags directly — when {@code source} is per-span (a request /
 *       statement);
 *   <li>build a memoized typed POJO that is itself a {@link TagContributor} — when {@code source}
 *       outlives the span (e.g. {@code Connection -> DbInfo}, extracted once and cached).
 * </ul>
 *
 * <p>This is an authoring aid meant to compile to ~zero — the opposite of a Decorator. It is a
 * {@link datadog.trace.api.function.Strategy}: bind it at the call site, preferably as a named
 * class held in a {@code static final} field of that concrete class ({@code XExtractor.INSTANCE});
 * a non-capturing lambda constant is accepted but is the weaker, speculative shape. The consumer,
 * {@link AgentSpan#setTagsFrom(Object, TagExtractor)}, is small so it inlines and each call site
 * sees the exact extractor type. Don't hold extractors in a field or collection of the abstract
 * type and dispatch from there.
 *
 * <p>Writes go to a {@link TagsWriter}, under one lock for the whole extraction (see {@link
 * AgentSpan#setTagsFrom(Object, TagExtractor)}); span-level state with a tag ({@code
 * resource.name}, {@code error}, ...) is set through that tag. Because the lock is held while
 * {@code extract} runs, keep it to reading the source: don't block, call back into tracing, or
 * touch another span. A virtual thread holding the lock stays pinned to its carrier before JDK 24.
 *
 * <p>Apply it with {@link AgentSpan#setTagsFrom(Object, TagExtractor)}.
 *
 * @param <T> the foreign source type to extract from
 */
@Strategy
@FunctionalInterface
public interface TagExtractor<T> {
  void extract(T source, TagsWriter writer);
}
