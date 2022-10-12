package datadog.trace.api.iast;

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

  void onStringConcat(@Nullable String left, @Nullable String right, @Nullable String result);

  void onStringConstructor(@Nullable CharSequence argument, @Nonnull String result);

  void onStringBuilderAppend(@Nullable StringBuilder builder, @Nullable CharSequence param);

  void onStringBuilderToString(@Nullable StringBuilder builder, @Nullable String result);
}
