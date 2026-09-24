package com.datadog.featureflag;

import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.util.AgentThreadFactory;

/** Compatibility adapter for agent-owned event pipelines. */
final class AgentRuntimeServices implements RuntimeServices {
  static final RuntimeServices INSTANCE = new AgentRuntimeServices();

  @Override
  public Thread newThread(String role, Runnable task) {
    if ("configuration".equals(role)) {
      return AgentThreadFactory.newAgentThread(
          AgentThreadFactory.AgentThread.FEATURE_FLAG_CONFIGURATION_POLLER, task);
    }
    return AgentThreadFactory.newAgentThread(
        "exposures".equals(role)
            ? AgentThreadFactory.AgentThread.FEATURE_FLAG_EXPOSURE_PROCESSOR
            : AgentThreadFactory.AgentThread.FEATURE_FLAG_EVALUATION_PROCESSOR,
        task);
  }

  @Override
  public void countMetric(String name, long value, String reason) {
    if (value > 0) {
      CoreMetricCollector.getInstance()
          .count(name, value, reason == null ? null : "reason:" + reason);
    }
  }
}
