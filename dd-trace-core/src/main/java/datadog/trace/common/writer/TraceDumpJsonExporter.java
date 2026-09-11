package datadog.trace.common.writer;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.core.DDSpan;
import java.util.Collection;
import java.util.List;

public class TraceDumpJsonExporter implements Writer {

  private static final JsonAdapter<Collection<DDSpan>> TRACE_ADAPTER =
      new Moshi.Builder()
          .add(DDSpanJsonAdapter.buildFactory(false))
          .build()
          .adapter(Types.newParameterizedType(Collection.class, DDSpan.class));
  private final StringBuilder dumpText;

  public TraceDumpJsonExporter() {
    dumpText = new StringBuilder();
  }

  public void write(final Collection<DDSpan> trace) {
    dumpText.append(TRACE_ADAPTER.toJson(trace));
    dumpText.append('\n');
  }

  @Override
  public void write(List<DDSpan> trace) {
    Collection<DDSpan> collectionTrace = trace;
    write(collectionTrace);
  }

  @Override
  public void start() {
    // do nothing
  }

  @Override
  public boolean flush() {
    // do nothing
    return true;
  }

  public String getDumpJson() {
    return dumpText.toString();
  }

  @Override
  public void close() {
    // do nothing
  }

  @Override
  public void incrementDropCounts(int spanCount) {}
}
