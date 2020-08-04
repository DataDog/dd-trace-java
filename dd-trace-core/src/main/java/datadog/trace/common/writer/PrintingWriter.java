package datadog.trace.common.writer;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.core.DDSpan;
import datadog.trace.core.interceptor.TraceHeuristicsEvaluator;
import datadog.trace.core.processor.TraceProcessor;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okio.Okio;

public class PrintingWriter implements Writer {
  private final TraceHeuristicsEvaluator collector = new TraceHeuristicsEvaluator();
  private final TraceProcessor processor = new TraceProcessor(collector);
  private final JsonWriter jsonWriter;
  private final JsonAdapter<Map<String, List<DDSpan>>> jsonAdapter;

  public PrintingWriter(final OutputStream outputStream, final boolean hexIds) {
    jsonWriter = JsonWriter.of(Okio.buffer(Okio.sink(outputStream)));

    this.jsonAdapter =
        new Moshi.Builder()
            .add(DDSpanJsonAdapter.buildFactory(hexIds))
            .build()
            .adapter(
                Types.newParameterizedType(
                    Map.class, String.class, Types.newParameterizedType(List.class, DDSpan.class)));
  }

  @Override
  public void write(final List<DDSpan> trace) {
    final List<DDSpan> processedTrace = processor.onTraceComplete(trace);
    try {
      jsonAdapter.toJson(jsonWriter, Collections.singletonMap("traces", processedTrace));
      jsonWriter.flush();
    } catch (final IOException e) {
      // do nothing
    }
  }

  @Override
  public void start() {
    // do nothing
  }

  @Override
  public void close() {
    // do nothing
  }

  @Override
  public void incrementTraceCount() {
    // do nothing
  }

  @Override
  public TraceHeuristicsEvaluator getTraceHeuristicsEvaluator() {
    return collector;
  }
}
