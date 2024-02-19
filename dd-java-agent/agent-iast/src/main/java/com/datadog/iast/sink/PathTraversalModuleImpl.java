package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.util.Iterators;
import datadog.trace.api.iast.sink.PathTraversalModule;
import java.io.File;
import java.net.URI;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class PathTraversalModuleImpl extends SinkModuleBase implements PathTraversalModule {

  public PathTraversalModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onPathTraversal(final @Nullable String path) {
    if (!canBeTainted(path)) {
      return;
    }
    checkInjection(VulnerabilityType.PATH_TRAVERSAL, path);
  }

  @Override
  public void onPathTraversal(final @Nullable String parent, final @Nonnull String child) {
    if (!canBeTainted(parent) && !canBeTainted(child)) {
      return;
    }
    if (parent == null) {
      checkInjection(VulnerabilityType.PATH_TRAVERSAL, child);
    } else {
      checkInjection(VulnerabilityType.PATH_TRAVERSAL, Iterators.of(parent, child));
    }
  }

  @Override
  public void onPathTraversal(final @Nonnull String first, final @Nonnull String[] more) {
    if (!canBeTainted(first) && !canBeTainted(more)) {
      return;
    }
    if (more.length == 0) {
      checkInjection(VulnerabilityType.PATH_TRAVERSAL, first);
    } else {
      checkInjection(VulnerabilityType.PATH_TRAVERSAL, Iterators.of(first, more));
    }
  }

  @Override
  public void onPathTraversal(final @Nonnull URI uri) {
    checkInjection(VulnerabilityType.PATH_TRAVERSAL, uri);
  }

  @Override
  public void onPathTraversal(final @Nullable File parent, final @Nonnull String child) {
    if (!canBeTainted(child)) {
      return;
    }
    if (parent == null) {
      checkInjection(VulnerabilityType.PATH_TRAVERSAL, child);
    } else {
      checkInjection(VulnerabilityType.PATH_TRAVERSAL, Iterators.of(parent, child));
    }
  }
}
