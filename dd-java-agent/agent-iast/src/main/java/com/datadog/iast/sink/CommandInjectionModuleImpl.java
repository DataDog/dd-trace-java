package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.rangesProviderFor;
import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.CommandInjectionModule;
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
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    checkInjection(VulnerabilityType.COMMAND_INJECTION, rangesProviderFor(to, cmdArray));
  }

  @SuppressWarnings("unchecked")
  @Override
  public void onRuntimeExec(@Nullable final String[] env, @Nonnull final String... command) {
    if (!canBeTainted(command) && !canBeTainted(env)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    checkInjection(
        VulnerabilityType.COMMAND_INJECTION,
        rangesProviderFor(to, env),
        rangesProviderFor(to, command));
  }

  @Override
  public void onProcessBuilderStart(@Nullable final List<String> command) {
    if (!canBeTainted(command)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    checkInjection(VulnerabilityType.COMMAND_INJECTION, rangesProviderFor(to, command));
  }
}
