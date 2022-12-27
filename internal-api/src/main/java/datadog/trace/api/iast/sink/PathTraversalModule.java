package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import java.io.File;
import java.net.URI;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface PathTraversalModule extends IastModule {

  void onPathTraversal(@Nonnull String path);

  void onPathTraversal(@Nullable String parent, @Nonnull String child);

  void onPathTraversal(@Nonnull String first, @Nonnull String[] more);

  void onPathTraversal(@Nonnull URI uri);

  void onPathTraversal(@Nullable File parent, @Nonnull String child);
}
