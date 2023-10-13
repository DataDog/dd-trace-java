package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.rangesProviderFor;
import static com.datadog.iast.taint.Tainteds.canBeTainted;
import static java.util.Arrays.asList;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.sink.PathTraversalModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class PathTraversalModuleImpl extends SinkModuleBase implements PathTraversalModule {

  @Override
  public void onPathTraversal(final @Nullable String path) {
    if (!canBeTainted(path)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    checkInjection(span, ctx, VulnerabilityType.PATH_TRAVERSAL, path);
  }

  @Override
  public void onPathTraversal(final @Nullable String parent, final @Nonnull String child) {
    if (!canBeTainted(parent) && !canBeTainted(child)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    if (parent == null) {
      checkInjection(span, ctx, VulnerabilityType.PATH_TRAVERSAL, child);
    } else {
      final TaintedObjects to = ctx.getTaintedObjects();
      checkInjection(
          span, VulnerabilityType.PATH_TRAVERSAL, rangesProviderFor(to, asList(parent, child)));
    }
  }

  @Override
  public void onPathTraversal(final @Nonnull String first, final @Nonnull String[] more) {
    if (!canBeTainted(first) && !canBeTainted(more)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    if (more.length == 0) {
      checkInjection(span, ctx, VulnerabilityType.PATH_TRAVERSAL, first);
    } else {
      final List<String> items = new ArrayList<>(more.length + 1);
      items.add(first);
      Collections.addAll(items, more);
      checkInjection(span, VulnerabilityType.PATH_TRAVERSAL, rangesProviderFor(to, items));
    }
  }

  @Override
  public void onPathTraversal(final @Nonnull URI uri) {
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    checkInjection(span, ctx, VulnerabilityType.PATH_TRAVERSAL, uri);
  }

  @Override
  public void onPathTraversal(final @Nullable File parent, final @Nonnull String child) {
    if (!canBeTainted(child)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    if (parent == null) {
      checkInjection(span, ctx, VulnerabilityType.PATH_TRAVERSAL, child);
    } else {
      final TaintedObjects to = ctx.getTaintedObjects();
      checkInjection(
          span, VulnerabilityType.PATH_TRAVERSAL, rangesProviderFor(to, asList(parent, child)));
    }
  }
}
