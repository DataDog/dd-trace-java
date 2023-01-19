package com.datadog.iast.propagation;

import com.datadog.iast.IastModuleBase;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.propagation.IOModule;
import java.io.InputStream;
import javax.annotation.Nullable;

public class IOModuleImpl extends IastModuleBase implements IOModule {
  @Override
  public void onConstruct(@Nullable InputStream inputStream, @Nullable InputStream result) {
    if (inputStream == null || result == null) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    if (taintedObjects.get(inputStream) != null && taintedObjects.get(result) == null) {
      taintedObjects.taint(result, Ranges.EMPTY);
    }
  }
}
