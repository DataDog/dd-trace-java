package datadog.trace.civisibility.config.api.dto.response;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;
import java.io.File;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public final class Meta {

  @Nullable public final String correlationId;
  @Nullable public final Map<String, BitSet> coverage;

  public Meta(@Nullable String correlationId, @Nullable Map<String, BitSet> coverage) {
    this.correlationId = correlationId;
    this.coverage = coverage;
  }

  public static final class JsonAdapter {

    public static final JsonAdapter INSTANCE = new JsonAdapter();

    @SuppressWarnings("unchecked")
    @FromJson
    public Meta fromJson(Map<String, Object> json) {
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

      return new Meta((String) json.get("correlation_id"), coverage);
    }

    @ToJson
    public Map<String, Object> toJson(Meta meta) {
      throw new UnsupportedOperationException();
    }
  }
}
