package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.events.attack.Attack010;

public interface ReportService {
  void reportAttack(Attack010 attack);
}
