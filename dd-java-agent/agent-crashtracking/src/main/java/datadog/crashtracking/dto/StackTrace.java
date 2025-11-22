package datadog.crashtracking.dto;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public final class StackTrace {
  private static final JsonAdapter<StackTrace> ADAPTER =
      new Moshi.Builder().build().adapter(StackTrace.class);
  private static final String FORMAT = "CrashTrackerV1";

  public final String format = FORMAT;
  public final StackFrame[] frames;

  public StackTrace(StackFrame[] frames) {
    this.frames = frames;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StackTrace that = (StackTrace) o;
    return Objects.equals(format, that.format) && Objects.deepEquals(frames, that.frames);
  }

  @Override
  public int hashCode() {
    return Objects.hash(format, Arrays.hashCode(frames));
  }

  public void writeAsJson(final JsonWriter writer) throws IOException {
    ADAPTER.toJson(writer, this);
  }
}
