package com.datadog.iast.source;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastModuleBase;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.SourceType;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.source.WebModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class WebModuleImpl extends IastModuleBase implements WebModule {

  @Override
  public void onParameterName(@Nullable final String paramName) {
    if (!canBeTainted(paramName)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        paramName, new Source(SourceType.REQUEST_PARAMETER_NAME, paramName, null));
  }

  @Override
  public void onParameterValue(
      @Nullable final String paramName, @Nullable final String paramValue) {
    if (!canBeTainted(paramValue)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        paramValue, new Source(SourceType.REQUEST_PARAMETER_VALUE, paramName, paramValue));
  }

  @Override
  public void onHeaderName(@Nullable final String headerName) {
    if (!canBeTainted(headerName)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        headerName, new Source(SourceType.REQUEST_HEADER_NAME, headerName, null));
  }

  @Override
  public <COOKIE> void onCookies(@Nonnull COOKIE[] cookies) {
    if (cookies == null || cookies.length == 0) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    for (COOKIE cookie : cookies) {
      if (cookie != null) {
        taintedObjects.taint(cookie, Ranges.EMPTY);
      }
    }
  }

  @Override
  public <COOKIE> void onCookieGetter(
      COOKIE self, String name, String result, byte sourceTypeValue) {
    if (!canBeTainted(result)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    if (taintedObjects.get(self) != null) {
      taintedObjects.taintInputString(result, new Source(sourceTypeValue, name, result));
    }
  }

  @Override
  public void onHeaderValue(@Nullable final String headerName, @Nullable final String headerValue) {
    if (!canBeTainted(headerValue)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        headerValue, new Source(SourceType.REQUEST_HEADER_VALUE, headerName, headerValue));
  }
}
