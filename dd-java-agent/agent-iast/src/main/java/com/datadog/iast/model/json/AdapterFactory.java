package com.datadog.iast.model.json;

import com.datadog.iast.model.Source;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityBatch;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Stateful adapter for {@link VulnerabilityBatch}. It will fill encode vulnerabilities, collecting
 * all references to {@link Source}, and replacing them with indexes. Then it will encode a sources
 * list at the end, in the same order that they were found within the vulnerabilities.
 */
class AdapterFactory implements JsonAdapter.Factory {

  private static final ThreadLocal<Context> CONTEXT_THREAD_LOCAL =
      ThreadLocal.withInitial(Context::new);

  private static class Context {
    private final List<Source> sources;
    private final Map<Source, Integer> sourceIndexMap;

    public Context() {
      sources = new ArrayList<>();
      sourceIndexMap = new HashMap<>();
    }
  }

  @Override
  @Nullable
  public JsonAdapter<?> create(Type type, Set<? extends Annotation> annotations, Moshi moshi) {
    final Class<?> rawType = Types.getRawType(type);
    if (Source.class.equals(rawType)) {
      for (final Annotation annotation : annotations) {
        if (SourceIndex.class.equals(annotation.annotationType())) {
          return new SourceIndexAdapter();
        }
      }
      return null;
    } else if (VulnerabilityBatch.class.equals(Types.getRawType(type))) {
      return new VulnerabilityBatchAdapter(moshi);
    }
    return null;
  }

  public static class SourceIndexAdapter extends JsonAdapter<Source> {

    @Override
    public void toJson(JsonWriter writer, @Nullable @SourceIndex Source value) throws IOException {
      if (value == null) {
        writer.nullValue();
        return;
      }
      final Context ctx = CONTEXT_THREAD_LOCAL.get();
      Integer index = ctx.sourceIndexMap.get(value);
      if (index == null) {
        index = ctx.sources.size();
        ctx.sources.add(value);
        ctx.sourceIndexMap.put(value, index);
      }
      writer.value(index);
    }

    @Nullable
    @Override
    public @SourceIndex Source fromJson(JsonReader reader) throws IOException {
      throw new UnsupportedOperationException("Source deserialization is not supported");
    }
  }

  public static class VulnerabilityBatchAdapter extends JsonAdapter<VulnerabilityBatch> {

    private final JsonAdapter<List<Source>> sourcesAdapter;

    private final JsonAdapter<List<Vulnerability>> vulnerabilitiesAdapter;

    public VulnerabilityBatchAdapter(final Moshi moshi) {
      sourcesAdapter = moshi.adapter(Types.newParameterizedType(List.class, Source.class));
      vulnerabilitiesAdapter =
          moshi.adapter(Types.newParameterizedType(List.class, Vulnerability.class));
    }

    @Override
    public void toJson(JsonWriter writer, @Nullable VulnerabilityBatch value) throws IOException {
      if (value == null) {
        writer.nullValue();
        return;
      }

      final Context ctx = CONTEXT_THREAD_LOCAL.get();
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
        CONTEXT_THREAD_LOCAL.remove();
      }
    }

    @Nullable
    @Override
    public VulnerabilityBatch fromJson(JsonReader reader) throws IOException {
      throw new UnsupportedOperationException(
          "VulnerabilityBatch deserialization is not supported");
    }
  }
}
