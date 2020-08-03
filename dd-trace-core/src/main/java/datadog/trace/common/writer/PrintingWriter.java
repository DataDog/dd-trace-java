package datadog.trace.common.writer;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okio.BufferedSink;
import okio.Okio;

public class PrintingWriter implements Writer {
  private final TraceProcessor processor = new TraceProcessor();
  private final BufferedSink sink;
  private final JsonAdapter<Map<String, List<DDSpan>>> jsonAdapter;

  public PrintingWriter(final OutputStream outputStream, final boolean hexIds) {
    sink = Okio.buffer(Okio.sink(outputStream));

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
      synchronized (sink) {
        jsonAdapter.toJson(sink, Collections.singletonMap("traces", processedTrace));
        sink.flush();
      }
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
}
