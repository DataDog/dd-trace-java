package com.datadog.iast.source;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Source;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.source.WebModule;
import javax.annotation.Nullable;

public class WebModuleImpl implements WebModule {

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
        paramName, new Source(SourceTypes.REQUEST_PARAMETER_NAME, paramName, null));
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
        paramValue, new Source(SourceTypes.REQUEST_PARAMETER_VALUE, paramName, paramValue));
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
        headerName, new Source(SourceTypes.REQUEST_HEADER_NAME, headerName, null));
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
        headerValue, new Source(SourceTypes.REQUEST_HEADER_VALUE, headerName, headerValue));
  }

  @Override
  public void onCookieValue(@Nullable final String cookieName, @Nullable final String cookieValue) {
    if (!canBeTainted(cookieValue)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        cookieValue, new Source(SourceTypes.REQUEST_COOKIE_VALUE, cookieName, cookieValue));
  }

  @Override
  public void onQueryString(@Nullable final String queryString) {
    if (!canBeTainted(queryString)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        queryString, new Source(SourceTypes.REQUEST_QUERY, null, queryString));
  }

  @Override
  public void onRequestPathParameter(@Nonnull String paramName, String value) {
    if (paramName == null || !canBeTainted(value)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        value, new Source(SourceTypes.REQUEST_PATH_PARAMETER, paramName, value));
  }
}
