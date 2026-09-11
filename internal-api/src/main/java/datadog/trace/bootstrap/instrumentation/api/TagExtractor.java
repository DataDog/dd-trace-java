package datadog.trace.bootstrap.instrumentation.api;

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
 * <p>This is an authoring aid meant to compile to ~zero — the opposite of a Decorator. Intended as
 * a {@code static final}, non-capturing lambda invoked from the integration's own advice: a
 * monomorphic call site the JIT devirtualizes and inlines (strictly better than dispatching through
 * the decorator hierarchy). Never route many extractors through one shared site. Targets {@link
 * AgentSpan} so it can also drive span-level state (resource name, error, status) during the
 * transition; that surface narrows as those fields migrate into the tag model.
 *
 * @param <T> the foreign source type to extract from
 */
@FunctionalInterface
public interface TagExtractor<T> {
  void extract(T source, AgentSpan span);
}
