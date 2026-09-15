package datadog.trace.test.agent.decoder.json.raw;

import static java.util.Collections.unmodifiableList;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import com.squareup.moshi.Types;
import datadog.trace.test.agent.decoder.DecodedMessage;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MessageJson decodes a JSON trace payload — a JSON array of traces, each a JSON array of spans in
 * the v0.4 shape, as exposed by the dd-apm-test-agent — into the shared {@link DecodedMessage}
 * model. Unlike the msgpack formats there is no message envelope, so the payload maps directly to
 * the list of traces.
 */
public final class MessageJson implements DecodedMessage {
  private static final Type LIST_OF_TRACES =
      Types.newParameterizedType(
          List.class, Types.newParameterizedType(List.class, SpanJson.class));
  private static final JsonAdapter<List<List<SpanJson>>> ADAPTER =
      new Moshi.Builder()
          .add(new MetricNumberAdapter())
          .add(new MetaStructObjectAdapter())
          .build()
          .adapter(LIST_OF_TRACES);

  private final List<DecodedTrace> traces;

  private MessageJson(List<DecodedTrace> traces) {
    this.traces = unmodifiableList(traces);
  }

  /** Decodes a JSON trace payload into a {@link MessageJson}. */
  public static MessageJson fromJson(String json) {
    List<List<SpanJson>> rawTraces;
    try {
      rawTraces = ADAPTER.fromJson(json);
    } catch (IOException | JsonDataException e) {
      throw new IllegalStateException("Failed to parse JSON traces: " + json, e);
    }
    List<DecodedTrace> traces = new ArrayList<>();
    if (rawTraces != null) {
      for (List<SpanJson> spans : rawTraces) {
        if (spans == null) {
          throw new IllegalStateException("Malformed JSON trace with a null trace: " + json);
        }
        List<DecodedSpan> decodedSpans = new ArrayList<>(spans.size());
        for (SpanJson span : spans) {
          if (span == null) {
            throw new IllegalStateException("Malformed JSON trace with a null span: " + json);
          }
          if (span.name == null
              || span.traceId == null
              || span.spanId == null
              || span.start == null
              || span.duration == null) {
            throw new IllegalStateException(
                "JSON span missing a required v0.4 field "
                    + "(name, trace_id, span_id, start, duration): "
                    + span);
          }
          span.resolveLinks();
          decodedSpans.add(span);
        }
        traces.add(new TraceJson(decodedSpans));
      }
    }
    return new MessageJson(traces);
  }

  /**
   * Decodes the numeric values of the {@code metrics} map. Moshi coerces every JSON number to
   * {@code Double}; this adapter instead reads the number from its literal form and preserves the
   * integral vs. fractional distinction the msgpack decoders produce (see {@code
   * SpanV04.unpackNumber}): integral values become {@code Integer} (or {@code Long} when they
   * overflow {@code int}, or {@code BigInteger} beyond {@code long} range), fractional values
   * become {@code Double}. Reading the literal rather than coercing through {@code double} keeps
   * every integral value exact — including those above 2^53 and above {@code Long.MAX_VALUE}.
   */
  static final class MetricNumberAdapter {
    @FromJson
    Number fromJson(JsonReader reader) throws IOException {
      String literal = reader.nextString();
      boolean fractional =
          literal.indexOf('.') >= 0 || literal.indexOf('e') >= 0 || literal.indexOf('E') >= 0;
      if (!fractional) {
        try {
          return Integer.valueOf(literal);
        } catch (NumberFormatException overflowsInt) {
          try {
            return Long.valueOf(literal);
          } catch (NumberFormatException overflowsLong) {
            // Integral magnitude beyond long range: keep it exact as a BigInteger.
            return new BigInteger(literal);
          }
        }
      }
      return Double.valueOf(literal);
    }

    @ToJson
    void toJson(JsonWriter writer, Number value) throws IOException {
      writer.value(value);
    }
  }

  /**
   * Decodes the arbitrarily-nested values of the {@code meta_struct} map. Moshi's generic {@code
   * Object} adapter materializes every JSON number as {@code Double}, every object as a map, and
   * every array as a list; this adapter instead mirrors the msgpack decoder (see {@code
   * SpanV04.convertValueToObject}) so both backends expose the same types for a nested leaf:
   *
   * <ul>
   *   <li>integers become {@code Long} (reading the literal keeps values above 2^53 exact, and
   *       values beyond {@code long} range become {@code BigInteger} rather than a lossy {@code
   *       Double}),
   *   <li>floating-point numbers become {@code Float},
   *   <li>objects become {@code Map<String, Object>} and arrays {@code List<Object>}, recursively,
   *   <li>strings, booleans, and JSON {@code null} are preserved as {@code String}/{@code
   *       Boolean}/{@code null}.
   * </ul>
   *
   * <p>Keeping the representations identical means a {@code metaStruct(...)} assertion behaves the
   * same whether the span came from the in-process msgpack backend or the dd-apm-test-agent JSON
   * backend. Binary leaves ({@code byte[]} on the msgpack side) have no JSON representation — the
   * agent fails to serialize them (HTTP 400) so they never reach this decoder; in practice {@code
   * meta_struct} carries only JSON-serializable data.
   */
  static final class MetaStructObjectAdapter {
    @FromJson
    Object fromJson(JsonReader reader) throws IOException {
      switch (reader.peek()) {
        case BEGIN_OBJECT:
          Map<String, Object> object = new LinkedHashMap<>();
          reader.beginObject();
          while (reader.hasNext()) {
            object.put(reader.nextName(), fromJson(reader));
          }
          reader.endObject();
          return object;
        case BEGIN_ARRAY:
          List<Object> array = new ArrayList<>();
          reader.beginArray();
          while (reader.hasNext()) {
            array.add(fromJson(reader));
          }
          reader.endArray();
          return array;
        case STRING:
          return reader.nextString();
        case NUMBER:
          return parseNumber(reader.nextString());
        case BOOLEAN:
          return reader.nextBoolean();
        case NULL:
          return reader.nextNull();
        default:
          throw new JsonDataException("Unexpected meta_struct token: " + reader.peek());
      }
    }

    private static Number parseNumber(String literal) {
      boolean fractional =
          literal.indexOf('.') >= 0 || literal.indexOf('e') >= 0 || literal.indexOf('E') >= 0;
      if (fractional) {
        return Float.valueOf(literal);
      }
      try {
        return Long.valueOf(literal);
      } catch (NumberFormatException overflowsLong) {
        // Integral magnitude beyond long range: keep it exact as a BigInteger.
        return new BigInteger(literal);
      }
    }
  }

  @Override
  public List<DecodedTrace> getTraces() {
    return this.traces;
  }
}
