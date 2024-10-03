package com.datadog.iast.model.json;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityBatch;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.sensitive.SensitiveHandler;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stateful adapter for {@link VulnerabilityBatch}. It will fill encode vulnerabilities, collecting
 * all references to {@link Source}, and replacing them with indexes. Then it will encode a sources
 * list at the end, in the same order that they were found within the vulnerabilities.
 */
class AdapterFactory implements JsonAdapter.Factory {

  static class Context {

    private static final ThreadLocal<Context> CONTEXT_THREAD_LOCAL =
        ThreadLocal.withInitial(Context::new);

    final List<Source> sources;
    final Map<Source, Integer> sourceIndexMap;
    final Map<Source, RedactionContext> sourceContext;
    @Nullable Vulnerability vulnerability;

    public Context() {
      sources = new ArrayList<>();
      sourceIndexMap = new HashMap<>();
      sourceContext = new HashMap<>();
    }

    public static Context get() {
      return CONTEXT_THREAD_LOCAL.get();
    }

    public static void set(@Nonnull final Context context) {
      CONTEXT_THREAD_LOCAL.set(context);
    }

    public static void remove() {
      CONTEXT_THREAD_LOCAL.remove();
    }

    public RedactionContext getRedaction(final Source source) {
      return sourceContext.computeIfAbsent(source, RedactionContext::new);
    }
  }

  @Override
  @Nullable
  public JsonAdapter<?> create(
      @Nonnull final Type type,
      @Nonnull final Set<? extends Annotation> annotations,
      @Nonnull final Moshi moshi) {
    final Class<?> rawType = Types.getRawType(type);
    if (Source.class.equals(rawType)) {
      if (hasSourceIndexAnnotation(annotations)) {
        return new SourceIndexAdapter();
      } else {
        return new SourceAdapter();
      }
    } else if (VulnerabilityBatch.class.equals(rawType)) {
      return new VulnerabilityBatchAdapter(moshi);
    } else if (Vulnerability.class.equals(rawType)) {
      return new VulnerabilityAdapter(this, moshi);
    } else if (Evidence.class.equals(rawType)) {
      return new EvidenceAdapter(moshi);
    } else if (VulnerabilityType.class.equals(rawType)) {
      return new VulnerabilityTypeAdapter();
    } else if (TruncatedVulnerabilities.class.equals(rawType)) {
      return new TruncatedVulnerabilitiesAdapter(moshi);
    }
    return null;
  }

  protected boolean hasSourceIndexAnnotation(@Nonnull final Set<? extends Annotation> annotations) {
    return annotations.stream()
        .anyMatch(annotation -> annotation.annotationType() == SourceIndex.class);
  }

  public static class SourceIndexAdapter extends FormattingAdapter<Source> {

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nullable @SourceIndex final Source value)
        throws IOException {
      if (value == null) {
        writer.nullValue();
        return;
      }
      final Context ctx = Context.get();
      Integer index = ctx.sourceIndexMap.get(value);
      if (index == null) {
        index = ctx.sources.size();
        ctx.sources.add(value);
        ctx.sourceIndexMap.put(value, index);
      }
      writer.value(index);
    }
  }

  public static class VulnerabilityBatchAdapter extends FormattingAdapter<VulnerabilityBatch> {

    private final JsonAdapter<List<Source>> sourcesAdapter;

    private final JsonAdapter<List<Vulnerability>> vulnerabilitiesAdapter;

    public VulnerabilityBatchAdapter(@Nonnull final Moshi moshi) {
      sourcesAdapter = moshi.adapter(Types.newParameterizedType(List.class, Source.class));
      vulnerabilitiesAdapter =
          moshi.adapter(Types.newParameterizedType(List.class, Vulnerability.class));
    }

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nullable final VulnerabilityBatch value)
        throws IOException {
      if (value == null) {
        writer.nullValue();
        return;
      }

      final Context ctx = Context.get();
      try {
        final List<Vulnerability> vulnerabilities = value.getVulnerabilities();
        writer.beginObject();
        if (vulnerabilities != null && !vulnerabilities.isEmpty()) {
          writer.name("vulnerabilities");
          vulnerabilitiesAdapter.toJson(writer, vulnerabilities);

          if (!ctx.sources.isEmpty()) {
            writer.name("sources");
            sourcesAdapter.toJson(writer, ctx.sources);
          }
        }

        writer.endObject();
      } finally {
        Context.remove();
      }
    }
  }

  public static class VulnerabilityAdapter extends FormattingAdapter<Vulnerability> {

    private final JsonAdapter<Vulnerability> adapter;

    public VulnerabilityAdapter(@Nonnull final AdapterFactory factory, @Nonnull final Moshi moshi) {
      adapter = moshi.nextAdapter(factory, Vulnerability.class, Collections.emptySet());
    }

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nullable final Vulnerability value)
        throws IOException {
      if (value == null) {
        return;
      }
      final Context ctx = Context.get();
      ctx.vulnerability = value;
      try {
        adapter.toJson(writer, value);
      } finally {
        ctx.vulnerability = null;
      }
    }
  }

  public static class RedactionContext {
    private final Source source;
    private final boolean sensitive;
    private boolean sensitiveRanges;
    @Nullable private String redactedValue;
    @Nullable private Set<VulnerabilityType> markedTypes;

    public RedactionContext(final Source source) {
      this.source = source;
      final SensitiveHandler handler = SensitiveHandler.get();
      this.sensitive = handler.isSensitive(source);
      if (this.sensitive) {
        this.redactedValue = handler.redactSource(source);
      }
      this.markedTypes = null;
    }

    public Source getSource() {
      return source;
    }

    public boolean isSensitive() {
      return sensitive;
    }

    public boolean shouldRedact() {
      return sensitive || sensitiveRanges;
    }

    @Nullable
    public String getRedactedValue() {
      return redactedValue;
    }

    public void markWithSensitiveRanges() {
      sensitiveRanges = true;
      if (redactedValue == null) {
        redactedValue = SensitiveHandler.get().redactSource(source);
      }
    }

    public void setMarkedTypes(@Nullable Set<VulnerabilityType> markedTypes) {
      this.markedTypes = markedTypes;
    }

    @Nullable
    public Set<VulnerabilityType> getMarkedTypes() {
      return markedTypes;
    }
  }
}
