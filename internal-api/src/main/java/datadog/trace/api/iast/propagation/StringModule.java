package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface StringModule extends IastModule {

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

  void onStringSubSequence(
      @Nullable String self, int beginIndex, int endIndex, @Nullable CharSequence result);
}
