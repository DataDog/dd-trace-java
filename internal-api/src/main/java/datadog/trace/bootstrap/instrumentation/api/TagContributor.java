package datadog.trace.bootstrap.instrumentation.api;

/**
 * An object that projects its own typed state onto a span — the inverse of a builder that takes
 * external {@code setTag} calls. Implemented by typed POJOs we own whose fields map to known tags
 * (e.g. {@code DbInfo}, git metadata, extracted context). {@code addTo} is a flat sweep of {@code
 * writer.setTag(KnownTags.X_ID, field)}, so it compiles to field loads and stores with no {@code
 * keyOf}.
 *
 * <p>This is an authoring aid meant to compile to ~zero — the opposite of a Decorator. Apply it at
 * a CONCRETE-type, monomorphic call site (the integration's own advice) so {@code addTo}
 * devirtualizes and inlines. Do <b>not</b> route many contributors through one shared {@code
 * List<TagContributor>} sink — that re-megamorphizes and recreates the decorator problem. Prefer
 * stateless implementations.
 *
 * <p>Writes go to a {@link TagsWriter}, under one lock for the whole sweep (see {@link
 * AgentSpan#setTagsFrom(TagContributor)}); span-level state with a tag ({@code resource.name},
 * {@code error}, ...) is set through that tag. See {@link TagExtractor} for the extrinsic
 * counterpart (foreign objects we do not own).
 */
public interface TagContributor {
  void addTo(TagsWriter writer);
}
