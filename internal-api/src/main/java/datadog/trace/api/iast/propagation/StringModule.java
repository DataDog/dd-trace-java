package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface StringModule extends IastModule {

  void onStringConcat(@Nonnull String left, @Nullable String right, @Nonnull String result);

  void onStringBuilderInit(@Nonnull CharSequence builder, @Nullable CharSequence param);

  void onStringBuilderAppend(@Nonnull CharSequence builder, @Nullable CharSequence param);

  void onStringBuilderToString(@Nonnull CharSequence builder, @Nonnull String result);

  void onStringConcatFactory(
      @Nullable String result,
      @Nullable String[] args,
      @Nullable String recipe,
      @Nullable Object[] dynamicConstants,
      @Nonnull int[] recipeOffsets);

  void onStringSubSequence(
      @Nonnull String self, int beginIndex, int endIndex, @Nullable CharSequence result);

  void onStringJoin(
      @Nullable String result, @Nonnull CharSequence delimiter, @Nonnull CharSequence[] elements);

  void onStringToUpperCase(@Nonnull String self, @Nullable String result);

  void onStringToLowerCase(@Nonnull String self, @Nullable String result);

  void onStringTrim(@Nonnull String self, @Nullable String result);

  void onStringRepeat(@Nonnull String self, int count, @Nonnull String result);

  void onStringConstructor(@Nonnull String self, @Nonnull String result);
}
