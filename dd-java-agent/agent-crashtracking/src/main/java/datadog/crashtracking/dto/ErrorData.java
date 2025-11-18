package datadog.crashtracking.dto;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.Objects;

public final class ErrorData {
  private static final JsonAdapter<ErrorData> ADAPTER =
      new Moshi.Builder().build().adapter(ErrorData.class);

  @Json(name = "is_crash")
  public final boolean isCrash;

  public final String kind;
  public final String message;

  @Json(name = "source_type")
  public final String sourceType = "crashtracking";

  public final StackTrace stack;

  public ErrorData(String kind, String message, StackTrace stack) {
    this(kind, message, stack, true);
  }

  public ErrorData(String kind, String message, StackTrace stack, boolean isCrash) {
    this.kind = kind;
    this.message = message;
    this.stack = stack;
    this.isCrash = isCrash;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ErrorData errorData = (ErrorData) o;
    return isCrash == errorData.isCrash
        && Objects.equals(kind, errorData.kind)
        && Objects.equals(message, errorData.message)
        && Objects.equals(sourceType, errorData.sourceType)
        && Objects.equals(stack, errorData.stack);
  }

  @Override
  public int hashCode() {
    return Objects.hash(isCrash, kind, message, sourceType, stack);
  }

  public void writeAsJson(final JsonWriter writer) throws IOException {
    ADAPTER.toJson(writer, this);
  }
}
