package datadog.trace.api.iast;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface IastModule {

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

  void onStringConcatFactory(
      @Nullable String[] args,
      @Nullable String result,
      @Nullable String recipe,
      @Nullable Object[] dynamicConstants,
      @Nonnull int[] recipeOffsets);
}
