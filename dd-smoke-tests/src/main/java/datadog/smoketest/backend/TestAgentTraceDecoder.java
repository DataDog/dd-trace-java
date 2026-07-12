package datadog.smoketest.backend;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the dd-apm-test-agent {@code /test/traces} JSON response into the same {@link
 * DecodedTrace} / {@link DecodedSpan} model the mock backend produces via the msgpack {@code
 * Decoder}. This is what lets the thin smoke matcher ({@code datadog.smoketest.trace}) work
 * uniformly across both backends (Q1).
 *
 * <p>The endpoint returns a JSON array of traces, each a JSON array of spans in the standard v0.4
 * shape.
 */
public final class TestAgentTraceDecoder {
  private static final Type LIST_OF_TRACES =
      Types.newParameterizedType(List.class, Types.newParameterizedType(List.class, RawSpan.class));
  private static final JsonAdapter<List<List<RawSpan>>> ADAPTER =
      new Moshi.Builder().build().adapter(LIST_OF_TRACES);

  private TestAgentTraceDecoder() {}

  /** Decodes a {@code /test/traces} JSON body into the shared decoded-trace model. */
  public static List<DecodedTrace> decode(String json) {
    List<List<RawSpan>> rawTraces;
    try {
      // IOException covers malformed JSON (JsonEncodingException); JsonDataException (unchecked)
      // covers a well-formed body of the wrong shape (e.g. an object where a trace array is
      // expected). Wrap both so callers always get the offending body for diagnosis.
      rawTraces = ADAPTER.fromJson(json);
    } catch (IOException | JsonDataException e) {
      throw new IllegalStateException("Failed to parse /test/traces response: " + json, e);
    }
    List<DecodedTrace> traces = new ArrayList<>();
    if (rawTraces != null) {
      for (List<RawSpan> spans : rawTraces) {
        List<DecodedSpan> decodedSpans =
            spans == null ? Collections.emptyList() : new ArrayList<>(spans);
        traces.add(new RawTrace(decodedSpans));
      }
    }
    return traces;
  }

  private static final class RawTrace implements DecodedTrace {
    private final List<DecodedSpan> spans;

    RawTrace(List<DecodedSpan> spans) {
      this.spans = Collections.unmodifiableList(spans);
    }

    @Override
    public List<DecodedSpan> getSpans() {
      return spans;
    }
  }

  // TODO verify these field names against a live ddapm-test-agent v1.44.0 /test/traces response.
  //  They follow the standard v0.4 trace shape (matching the msgpack Decoder's fields), but have
  // not
  //  yet been validated end-to-end against the container — do so when S3/S8 run with Docker.
  static final class RawSpan implements DecodedSpan {
    String service;
    String name;
    String resource;
    String type;

    @Json(name = "trace_id")
    long traceId;

    @Json(name = "span_id")
    long spanId;

    @Json(name = "parent_id")
    long parentId;

    long start;
    long duration;
    int error;
    Map<String, String> meta;

    @Json(name = "meta_struct")
    Map<String, Object> metaStruct;

    Map<String, Double> metrics;

    @Override
    public String getService() {
      return service;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getResource() {
      return resource;
    }

    @Override
    public long getTraceId() {
      return traceId;
    }

    @Override
    public long getSpanId() {
      return spanId;
    }

    @Override
    public long getParentId() {
      return parentId;
    }

    @Override
    public long getStart() {
      return start;
    }

    @Override
    public long getDuration() {
      return duration;
    }

    @Override
    public int getError() {
      return error;
    }

    @Override
    public Map<String, String> getMeta() {
      return meta;
    }

    @Override
    public Map<String, Object> getMetaStruct() {
      return metaStruct;
    }

    @Override
    public Map<String, Number> getMetrics() {
      // The decoder model exposes Number; Moshi deserializes JSON numbers as Double.
      return metrics == null ? null : new HashMap<>(metrics);
    }

    @Override
    public String getType() {
      return type;
    }
  }
}
