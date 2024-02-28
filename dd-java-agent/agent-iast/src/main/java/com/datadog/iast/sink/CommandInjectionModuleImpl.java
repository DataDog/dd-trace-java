package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.util.Iterators;
import datadog.trace.api.iast.sink.CommandInjectionModule;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CommandInjectionModuleImpl extends SinkModuleBase implements CommandInjectionModule {

  public CommandInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onRuntimeExec(@Nullable final String[] cmdArray) {
    if (!canBeTainted(cmdArray)) {
      return;
    }
    checkInjection(VulnerabilityType.COMMAND_INJECTION, Iterators.of(cmdArray));
  }

  @Override
  public void onRuntimeExec(@Nullable final String[] env, @Nonnull final String[] command) {
    if (!canBeTainted(command) && !canBeTainted(env)) {
      return;
    }
    checkInjection(
        VulnerabilityType.COMMAND_INJECTION,
        Iterators.join(Iterators.of(env), Iterators.of(command)));
  }

  @Override
  public void onProcessBuilderStart(@Nullable final List<String> command) {
    if (!canBeTainted(command)) {
      return;
    }
    checkInjection(VulnerabilityType.COMMAND_INJECTION, command.iterator());
  }
}
