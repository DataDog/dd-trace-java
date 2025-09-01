package com.datadog.debugger.agent;

import datadog.trace.api.Config;
import datadog.trace.api.config.DebuggerConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.debugger.DebuggerConfigUpdate;
import datadog.trace.api.debugger.DebuggerConfigUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DefaultDebuggerConfigUpdater implements DebuggerConfigUpdater {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDebuggerConfigUpdater.class);

  @Override
  public void updateConfig(DebuggerConfigUpdate update) {
    startOrStopFeature(
        DebuggerConfig.DYNAMIC_INSTRUMENTATION_ENABLED,
        update.getDynamicInstrumentationEnabled(),
        DebuggerAgent::startDynamicInstrumentation,
        DebuggerAgent::stopDynamicInstrumentation);
    startOrStopFeature(
        DebuggerConfig.EXCEPTION_REPLAY_ENABLED,
        update.getExceptionReplayEnabled(),
        DebuggerAgent::startExceptionReplay,
        DebuggerAgent::stopExceptionReplay);
    startOrStopFeature(
        TraceInstrumentationConfig.CODE_ORIGIN_FOR_SPANS_ENABLED,
        update.getCodeOriginEnabled(),
        DebuggerAgent::startCodeOriginForSpans,
        DebuggerAgent::stopCodeOriginForSpans);
    startOrStopFeature(
        DebuggerConfig.DISTRIBUTED_DEBUGGER_ENABLED,
        update.getDistributedDebuggerEnabled(),
        DebuggerAgent::startDistributedDebugger,
        DebuggerAgent::stopDistributedDebugger);
  }

  @Override
  public boolean isDynamicInstrumentationEnabled() {
    return DebuggerAgent.dynamicInstrumentationEnabled.get();
  }

  @Override
  public boolean isExceptionReplayEnabled() {
    return DebuggerAgent.exceptionReplayEnabled.get();
  }

  @Override
  public boolean isCodeOriginEnabled() {
    return DebuggerAgent.codeOriginEnabled.get();
  }

  @Override
  public boolean isDistributedDebuggerEnabled() {
    return DebuggerAgent.distributedDebuggerEnabled.get();
  }

  private static boolean isExplicitlyDisabled(String booleanKey) {
    return Config.get().configProvider().isSet(booleanKey)
        && !Config.get().configProvider().getBoolean(booleanKey);
  }

  private static void startOrStopFeature(
      String booleanKey, Boolean currentStatus, Runnable start, Runnable stop) {
    if (isExplicitlyDisabled(booleanKey)) {
      LOGGER.debug("Feature {} is explicitly disabled", booleanKey);
      return;
    }
    if (currentStatus != null) {
      if (currentStatus) {
        start.run();
      } else {
        stop.run();
      }
    }
  }
}
