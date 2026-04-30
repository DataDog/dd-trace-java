package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;
import java.io.File;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

final class MetaDto {

  @Nullable final String correlationId;
  @Nullable final Map<String, BitSet> coverage;

  MetaDto(@Nullable String correlationId, @Nullable Map<String, BitSet> coverage) {
    this.correlationId = correlationId;
    this.coverage = coverage;
  }

  static final class JsonAdapter {

    static final JsonAdapter INSTANCE = new JsonAdapter();

    @SuppressWarnings("unchecked")
    @FromJson
    public MetaDto fromJson(Map<String, Object> json) {
      if (json == null) {
        return null;
      }

      Map<String, BitSet> coverage;
      Map<String, String> encodedCoverage = (Map<String, String>) json.get("coverage");
      if (encodedCoverage != null) {
        coverage = new HashMap<>();
        for (Map.Entry<String, String> e : encodedCoverage.entrySet()) {
          String relativeSourceFilePath = e.getKey();
          String normalizedPath =
              relativeSourceFilePath.startsWith(File.separator)
                  ? relativeSourceFilePath.substring(1)
                  : relativeSourceFilePath;
          byte[] decodedLines = Base64.getDecoder().decode(e.getValue());
          coverage.put(normalizedPath, BitSet.valueOf(decodedLines));
        }
      } else {
        coverage = null;
      }

      return new MetaDto((String) json.get("correlation_id"), coverage);
    }

    @ToJson
    public Map<String, Object> toJson(MetaDto metaDto) {
      throw new UnsupportedOperationException();
    }
  }
}
