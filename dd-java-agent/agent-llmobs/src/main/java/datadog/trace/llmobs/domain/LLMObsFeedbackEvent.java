package datadog.trace.llmobs.domain;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.DDTraceApiInfo;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.llmobs.LLMObsIntakeWorker;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * An end-user feedback event, as sent to the eval metric intake.
 *
 * <p>Feedback shares the {@code evaluation_metric} envelope with {@link LLMObsEval} and is told
 * apart on the wire by {@code event_kind}, mirroring dd-trace-py and dd-trace-js.
 */
public final class LLMObsFeedbackEvent {

  private static final String EVENT_KIND_FEEDBACK = "feedback";

  private final LLMObs.Feedback feedback;
  private final String mlApp;
  private final List<String> tags;

  public LLMObsFeedbackEvent(LLMObs.Feedback feedback, String mlApp) {
    this.feedback = feedback;
    this.mlApp = mlApp;
    this.tags = buildTags(feedback.getTags(), mlApp);
  }

  public LLMObs.Feedback getFeedback() {
    return feedback;
  }

  public String getMlApp() {
    return mlApp;
  }

  public List<String> getTags() {
    return tags;
  }

  private static List<String> buildTags(@Nullable Map<String, Object> userTags, String mlApp) {
    return IntakeTags.flatten(
        userTags, "ddtrace.version:" + DDTraceApiInfo.VERSION, "ml_app:" + mlApp);
  }

  /**
   * Returns a serializer turning a batch of feedback events into an intake request body.
   *
   * @return the batch serializer
   */
  public static LLMObsIntakeWorker.BatchSerializer<LLMObsFeedbackEvent> batchSerializer() {
    Moshi moshi = new Moshi.Builder().add(LLMObsFeedbackEvent.class, new Adapter()).build();
    JsonAdapter<Request> requestAdapter = moshi.adapter(Request.class);
    return batch -> requestAdapter.toJson(new Request(batch));
  }

  public static final class Adapter extends JsonAdapter<LLMObsFeedbackEvent> {
    private final JsonAdapter<Object> valueAdapter =
        new Moshi.Builder().build().adapter(Object.class);

    @Nullable
    @Override
    public LLMObsFeedbackEvent fromJson(JsonReader reader) {
      return null;
    }

    @Override
    public void toJson(JsonWriter writer, @Nullable LLMObsFeedbackEvent event) throws IOException {
      if (event == null) {
        throw new JsonDataException("unexpectedly got null llm obs feedback event");
      }
      LLMObs.Feedback feedback = event.feedback;

      writer.beginObject();
      writer.name("event_kind").value(EVENT_KIND_FEEDBACK);
      // Exactly one target, enforced by the builder.
      writer.name(feedback.getTargetType().getWireKey()).value(feedback.getTargetValue());
      writer.name("label").value(feedback.getLabel());
      writer.name("metric_type").value(feedback.getMetricType().toString());
      writer.name(feedback.getMetricType() + "_value");
      valueAdapter.toJson(writer, feedback.getValue());
      writer.name("ml_app").value(event.mlApp);
      writer.name("timestamp_ms").value(feedback.getTimestampMs());

      writer.name("submitter").beginObject();
      writer.name("id").value(feedback.getSubmitter().getId());
      if (feedback.getSubmitter().getType() != null) {
        writer.name("type").value(feedback.getSubmitter().getType());
      }
      writer.endObject();

      if (feedback.getAssessment() != null) {
        writer.name("assessment").value(feedback.getAssessment().toString());
      }
      if (feedback.getReasoning() != null) {
        writer.name("reasoning").value(feedback.getReasoning());
      }

      writer.name("tags").beginArray();
      for (String tag : event.tags) {
        writer.value(tag);
      }
      writer.endArray();

      writer.endObject();
    }
  }

  /** The request envelope, identical in shape to the one used for evaluations. */
  public static final class Request {
    public final Data data;

    public static class Data {
      public final String type = "evaluation_metric";
      public Attributes attributes;
    }

    public static class Attributes {
      public List<LLMObsFeedbackEvent> metrics;
    }

    public Request(List<LLMObsFeedbackEvent> metrics) {
      this.data = new Data();
      this.data.attributes = new Attributes();
      this.data.attributes.metrics = metrics;
    }
  }
}
