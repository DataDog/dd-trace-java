package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.events.attack.Attack010;
import java.io.Closeable;

public interface ReportService extends Closeable {
  void reportAttack(Attack010 attack);

  void close();
}
