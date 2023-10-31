package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.rangesProviderFor;
import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.CommandInjectionModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CommandInjectionModuleImpl extends SinkModuleBase implements CommandInjectionModule {

  public CommandInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onRuntimeExec(@Nullable final String... cmdArray) {
    if (!canBeTainted(cmdArray)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    checkInjection(
        span, VulnerabilityType.COMMAND_INJECTION, rangesProviderFor(taintedObjects, cmdArray));
  }

  @Override
  public void onRuntimeExec(@Nullable final String[] env, @Nonnull final String... command) {
    if (!canBeTainted(command) && !canBeTainted(env)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    checkInjection(
        span,
        VulnerabilityType.COMMAND_INJECTION,
        rangesProviderFor(taintedObjects, env),
        rangesProviderFor(taintedObjects, command));
  }

  @Override
  public void onProcessBuilderStart(@Nullable final List<String> command) {
    if (!canBeTainted(command)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    checkInjection(
        span, VulnerabilityType.COMMAND_INJECTION, rangesProviderFor(taintedObjects, command));
  }
}
