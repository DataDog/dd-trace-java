package datadog.trace.common.writer;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.core.DDSpan;
import java.util.List;

public class TraceDumpWriter implements Writer {

  private StringBuilder dumpText;
  private static final JsonAdapter<List<DDSpan>> TRACE_ADAPTER =
      new Moshi.Builder()
          .add(DDSpanJsonAdapter.buildFactory(false))
          .build()
          .adapter(Types.newParameterizedType(List.class, DDSpan.class));

  public TraceDumpWriter() {
    dumpText = new StringBuilder();
  }

  @Override
  public void write(final List<DDSpan> trace) {
    dumpText.append(TRACE_ADAPTER.toJson(trace));
    dumpText.append("\n");
  }

  public String getDumpText() {
    return dumpText.toString();
  }

  @Override
  public void start() {
    // Do nothing
  }

  @Override
  public boolean flush() {
    return true;
  }

  @Override
  public void close() {
    // Do nothing
  }

  @Override
  public void incrementDropCounts(int spanCount) {}
}
