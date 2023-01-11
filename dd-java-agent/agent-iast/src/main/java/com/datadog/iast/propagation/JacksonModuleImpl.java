package com.datadog.iast.propagation;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastModuleBase;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.propagation.JacksonModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class JacksonModuleImpl extends IastModuleBase implements JacksonModule {

  @Override
  public void onJsonFactoryCreateParser(@Nullable Object input, @Nullable Object jsonParser) {
    if (jsonParser == null || input == null) {
      return;
    }
    taintIfParameterIsTainted(jsonParser, input);
  }

  @Override
  public void onJsonFactoryCreateParser(@Nullable String content, @Nullable Object jsonParser) {
    if (jsonParser == null || !canBeTainted(content)) {
      return;
    }
    taintIfParameterIsTainted(jsonParser, content);
  }

  @Override
  public void onJsonParserGetString(@Nonnull Object jsonParser, @Nullable String result) {
    if (!canBeTainted(result)) {
      return;
    }
    taintIfParameterIsTainted(result, jsonParser);
  }

  private void taintIfParameterIsTainted(final Object toTaint, final Object parameter) {
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    if (taintedObjects.get(parameter) != null) {
      taintedObjects.taint(toTaint, Ranges.EMPTY);
    }
  }
}
