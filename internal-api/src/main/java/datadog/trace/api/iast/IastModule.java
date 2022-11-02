package datadog.trace.api.iast;

import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface IastModule {

  void onCipherAlgorithm(@Nullable String algorithm);

  void onHashingAlgorithm(@Nullable String algorithm);

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

  void onStringConstructor(@Nullable CharSequence argument, @Nonnull String result);

  void onStringBuilderInit(@Nonnull StringBuilder builder, @Nullable CharSequence param);

  void onStringBuilderAppend(@Nonnull StringBuilder builder, @Nullable CharSequence param);

  void onStringBuilderToString(@Nonnull StringBuilder builder, @Nonnull String result);

  void onStringConcatFactory(
      @Nullable String[] args,
      @Nullable String result,
      @Nullable String recipe,
      @Nullable Object[] dynamicConstants,
      @Nonnull int[] recipeOffsets);

  void onRuntimeExec(@Nullable String... command);

  void onProcessBuilderStart(@Nullable List<String> command);

  String onStringFormat(@Nullable Locale l, @Nonnull String fmt, @Nullable Object[] args);
}
