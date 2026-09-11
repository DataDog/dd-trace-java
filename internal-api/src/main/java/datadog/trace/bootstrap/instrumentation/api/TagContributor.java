package datadog.trace.bootstrap.instrumentation.api;

/**
 * An object that projects its own typed state onto a span — the inverse of a builder that takes
 * external {@code setTag} calls. Implemented by typed POJOs we own whose fields map to known tags
 * (e.g. {@code DbInfo}, git metadata, extracted context). {@code addTo} is a flat sweep of {@code
 * span.setTag(...)}; once the id-arm lands it becomes {@code setTag(KnownTagIds.X, field)}, so it
 * compiles to field-loads + positional stores with no {@code keyOf}.
 *
 * <p>This is an authoring aid meant to compile to ~zero — the opposite of a Decorator. Apply it at
 * a CONCRETE-type, monomorphic call site (the integration's own advice) so {@code addTo}
 * devirtualizes and inlines. Do <b>not</b> route many contributors through one shared {@code
 * List<TagContributor>} sink — that re-megamorphizes and recreates the decorator problem. Prefer
 * stateless implementations.
 *
 * <p>Targets {@link AgentSpan} for now (so it can also drive span-level state — resource name,
 * error, measured — that is not yet expressed as tags). As those span fields migrate into the tag
 * model, this surface narrows toward a future {@code addTo(TagMap)}. See {@link TagExtractor} for
 * the extrinsic counterpart (foreign objects we do not own).
 */
public interface TagContributor {
  void addTo(AgentSpan span);
}
