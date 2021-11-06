package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.dtos.intake.IntakeBatch;
import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

public final class ReportSerializer {
  private static final JsonAdapter<IntakeBatch> INTAKE_BATCH_ADAPTER;

  static {
    Moshi moshi = new Moshi.Builder().add(new MoshiAdapter()).build();

    INTAKE_BATCH_ADAPTER = moshi.adapter(IntakeBatch.class);
  }

  private ReportSerializer() {}

  public static JsonAdapter<IntakeBatch> getIntakeBatchAdapter() {
    return INTAKE_BATCH_ADAPTER;
  }

  static class MoshiAdapter {
    @ToJson
    String toJson(Instant instant) {
      return instant.toString();
    }

    @FromJson
    Instant toInstant(String s) {
      return DateTimeFormatter.ISO_INSTANT.parse(s, Instant::from);
    }
  }
}
