package datadog.trace.common.writer;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okio.BufferedSink;
import okio.Okio;

public class PrintingWriter implements Writer {
  private final BufferedSink sink;
  private final JsonAdapter<Map<String, List<List<DDSpan>>>> jsonAdapter;

  public PrintingWriter(final OutputStream outputStream, final boolean hexIds) {
    sink = Okio.buffer(Okio.sink(outputStream));

    this.jsonAdapter =
        new Moshi.Builder()
            .add(DDSpanJsonAdapter.buildFactory(hexIds))
            .build()
            .adapter(
                Types.newParameterizedType(
                    Map.class,
                    String.class,
                    Types.newParameterizedType(
                        List.class, Types.newParameterizedType(List.class, DDSpan.class))));
  }

  @Override
  public void write(final List<DDSpan> trace) {
    final List<List<DDSpan>> tracesList = Collections.singletonList(trace);
    try {
      synchronized (sink) {
        jsonAdapter.toJson(sink, Collections.singletonMap("traces", tracesList));
        sink.writeString("\n", StandardCharsets.UTF_8);
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
  public boolean flush() {
    return true;
  }

  @Override
  public void close() {
    // do nothing
  }

  @Override
  public void incrementDropCounts(int spanCount) {}
}
