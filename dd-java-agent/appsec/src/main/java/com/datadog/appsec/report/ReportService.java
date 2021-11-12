package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.events.AppSecEvent100;
import java.io.Closeable;

public interface ReportService extends Closeable {
  void reportEvent(AppSecEvent100 event);

  void close();
}
