package com.datadog.iast.model.json;

import com.squareup.moshi.JsonWriter;
import datadog.trace.api.Config;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class TruncationUtils {
  private static final int VALUE_MAX_LENGTH = Config.get().getIastTruncationMaxValueLength();
  private static final String TRUNCATED = "truncated";
  private static final String RIGHT = "right";

  private TruncationUtils() {}

  public static void writeTruncableValue(@Nonnull JsonWriter writer, @Nullable String value)
      throws IOException {
    if (value != null && value.length() > VALUE_MAX_LENGTH) {
      writer.value(value.substring(0, VALUE_MAX_LENGTH));
      writer.name(TRUNCATED);
      writer.value(RIGHT);
    } else {
      writer.value(value);
    }
  }
}
