package datadog.trace.api.iast;

import java.io.File;
import java.net.URI;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface IastModule {

  void onCipherAlgorithm(@Nullable String algorithm);

  void onHashingAlgorithm(@Nullable String algorithm);

  void onJdbcQuery(@Nonnull String queryString);

  /**
   * An HTTP request parameter name is used. This should be used when it cannot be determined
   * whether the parameter comes in the query string or body (e.g. servlet's getParameter).
   */
  void onParameterName(@Nullable String paramName);

  /**
   * An HTTP request parameter value is used. This should be used when it cannot be determined
   * whether the parameter comes in the query string or body (e.g. servlet's getParameter).
   */
  void onParameterValue(@Nullable String paramName, @Nullable String paramValue);

  void onStringConcat(@Nonnull String left, @Nullable String right, @Nonnull String result);

  void onStringBuilderInit(@Nonnull StringBuilder builder, @Nullable CharSequence param);

  void onStringBuilderAppend(@Nonnull StringBuilder builder, @Nullable CharSequence param);

  void onStringBuilderToString(@Nonnull StringBuilder builder, @Nonnull String result);

  void onStringConcatFactory(
      @Nullable String result,
      @Nullable String[] args,
      @Nullable String recipe,
      @Nullable Object[] dynamicConstants,
      @Nonnull int[] recipeOffsets);

  void onRuntimeExec(@Nonnull String... command);

  void onProcessBuilderStart(@Nonnull List<String> command);

  void onPathTraversal(@Nonnull String path);

  void onPathTraversal(@Nullable String parent, @Nonnull String child);

  void onPathTraversal(@Nonnull String first, @Nonnull String[] more);

  void onPathTraversal(@Nonnull URI uri);

  void onPathTraversal(@Nullable File parent, @Nonnull String child);
}
