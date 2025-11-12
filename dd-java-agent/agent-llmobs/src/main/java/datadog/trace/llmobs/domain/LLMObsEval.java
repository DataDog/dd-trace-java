package datadog.trace.llmobs.domain;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public abstract class LLMObsEval {
  private static final String METRIC_TYPE_SCORE = "score";
  private static final String METRIC_TYPE_CATEGORICAL = "categorical";

  public final String trace_id;
  public final String span_id;
  public final long timestamp_ms;
  public final String ml_app;
  public final String metric_type;
  public final String label;
  public final List<String> tags;

  public LLMObsEval(
      String traceID,
      String spanID,
      long timestampMs,
      String mlApp,
      String metricType,
      String label,
      Map<String, Object> tags) {
    this.trace_id = traceID;
    this.span_id = spanID;
    this.timestamp_ms = timestampMs;
    this.ml_app = mlApp;
    this.metric_type = metricType;
    this.label = label;
    if (tags != null) {
      List<String> tagsList = new ArrayList<>(tags.size());
      for (Map.Entry<String, Object> entry : tags.entrySet()) {
        tagsList.add(entry.getKey() + ":" + entry.getValue());
      }
      this.tags = tagsList;
    } else {
      this.tags = null;
    }
  }

  public static final class Adapter extends JsonAdapter<LLMObsEval> {
    private final Moshi moshi = new Moshi.Builder().build();
    private final JsonAdapter<Score> scoreJsonAdapter = moshi.adapter(Score.class);
    private final JsonAdapter<Categorical> categoricalJsonAdapter =
        moshi.adapter(Categorical.class);

    @Nullable
    @Override
    public LLMObsEval fromJson(JsonReader reader) {
      return null;
    }

    @Override
    public void toJson(JsonWriter writer, LLMObsEval value) throws IOException {
      if (value == null) {
        throw new JsonDataException("unexpectedly got null llm obs eval ");
      }
      if (value instanceof Score) {
        scoreJsonAdapter.toJson(writer, (Score) value);
      } else if (value instanceof Categorical) {
        categoricalJsonAdapter.toJson(writer, (Categorical) value);
      } else {
        throw new JsonDataException("Unknown llm obs eval subclass: " + value.getClass());
      }
    }
  }

  public static final class Score extends LLMObsEval {
    public final double score_value;

    public Score(
        String traceID,
        long spanID,
        long timestampMS,
        String mlApp,
        String label,
        Map<String, Object> tags,
        double scoreValue) {
      super(traceID, String.valueOf(spanID), timestampMS, mlApp, METRIC_TYPE_SCORE, label, tags);
      this.score_value = scoreValue;
    }
  }

  public static final class Categorical extends LLMObsEval {
    public final String categorical_value;

    public Categorical(
        String traceID,
        long spanID,
        long timestampMS,
        String mlApp,
        String label,
        Map<String, Object> tags,
        String categoricalValue) {
      super(
          traceID,
          String.valueOf(spanID),
          timestampMS,
          mlApp,
          METRIC_TYPE_CATEGORICAL,
          label,
          tags);
      this.categorical_value = categoricalValue;
    }
  }

  public static final class Request {
    public final Data data;

    public static class Data {
      public final String type = "evaluation_metric";
      public Attributes attributes;
    }

    public static class Attributes {
      public List<LLMObsEval> metrics;
    }

    public Request(List<LLMObsEval> metrics) {
      this.data = new Data();
      this.data.attributes = new Attributes();
      this.data.attributes.metrics = metrics;
    }
  }
}
