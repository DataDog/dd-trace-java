package datadog.test.agent;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class AgentTrace {
  private static JsonAdapter<List<AgentSpan>> singleAdapter;
  private static JsonAdapter<List<List<AgentSpan>>> collectionAdapter;

  private final List<AgentSpan> spans;

  public AgentTrace(List<AgentSpan> spans) {
    this.spans = spans;
  }

  public List<AgentSpan> spans() {
    return this.spans;
  }

  @Override
  public String toString() {
    return this.spans.toString();
  }

  public static AgentTrace fromJson(String json) throws IOException {
    checkAdapters();
    List<AgentSpan> spans = AgentTrace.singleAdapter.fromJson(json);
    if (spans == null) {
      return null;
    }
    return new AgentTrace(spans);
  }

  public static List<AgentTrace> fromJsonArray(String json) throws IOException {
    checkAdapters();
    List<List<AgentSpan>> traces = AgentTrace.collectionAdapter.fromJson(json);
    if (traces == null) {
      return emptyList();
    }
    return traces.stream().map(AgentTrace::new).collect(toList());
  }

  private static void checkAdapters() {
    if (AgentTrace.singleAdapter == null || AgentTrace.collectionAdapter == null) {
      Moshi moshi = new Moshi.Builder()
          .add(Duration.class, new AgentSpan.DurationAdapter())
          .add(Instant.class, new AgentSpan.InstantAdapter())
          .build();
      Type traceType = Types.newParameterizedType(List.class, AgentSpan.class);
      AgentTrace.singleAdapter = moshi.adapter(traceType);
      Type traceArray = Types.newParameterizedType(List.class, traceType);
      AgentTrace.collectionAdapter = moshi.adapter(traceArray);
    }
  }
}
