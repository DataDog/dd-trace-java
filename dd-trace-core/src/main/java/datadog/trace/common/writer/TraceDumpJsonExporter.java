package datadog.trace.common.writer;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipOutputStream;

public class TraceDumpJsonExporter implements Writer {

  private static final JsonAdapter<Collection<DDSpan>> TRACE_ADAPTER =
      new Moshi.Builder()
          .add(DDSpanJsonAdapter.buildFactory(false))
          .build()
          .adapter(Types.newParameterizedType(Collection.class, DDSpan.class));
  private StringBuilder dumpText;
  private ZipOutputStream zip;

  public TraceDumpJsonExporter(ZipOutputStream zip) {
    this.zip = zip;
    dumpText = new StringBuilder();
  }

  public void write(final Collection<DDSpan> trace) {
    dumpText.append(TRACE_ADAPTER.toJson(trace));
    dumpText.append('\n');
  }

  @Override
  public void write(List<DDSpan> trace) {
    // Do nothing
  }

  @Override
  public void start() {
    // do nothing
  }

  @Override
  public boolean flush() {
    try {
      TracerFlare.addText(zip, "pending_traces.txt", dumpText.toString());
    } catch (IOException e) {
      // do nothing
    }
    return true;
  }

  @Override
  public void close() {
    // do nothing
  }

  @Override
  public void incrementDropCounts(int spanCount) {}
}
