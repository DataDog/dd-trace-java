package datadog.crashtracking.dto;

import com.squareup.moshi.Json;
import java.util.Objects;
import datadog.trace.util.HashingUtils;

public final class ErrorData {
  @Json(name = "is_crash")
  public final boolean isCrash = true;

  public final String kind;
  public final String message;

  @Json(name = "source_type")
  public final String sourceType = "Crashtracking";

  public final StackTrace stack;

  public ErrorData(String kind, String message, StackTrace stack) {
    this.kind = kind;
    this.message = message;
    this.stack = stack;
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
    return HashingUtils.hash(isCrash, kind, message, sourceType, stack);
  }
}
