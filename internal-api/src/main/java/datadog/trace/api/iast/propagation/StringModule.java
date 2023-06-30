package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface StringModule extends IastModule {

  void onStringConcat(
      @Nonnull CharSequence left, @Nullable CharSequence right, @Nonnull CharSequence result);

  void onStringAppend(@Nonnull CharSequence self, @Nullable CharSequence param);

  void onStringToString(@Nonnull CharSequence self, @Nonnull CharSequence result);

  void onStringConcatFactory(
      @Nullable String result,
      @Nullable String[] args,
      @Nullable String recipe,
      @Nullable Object[] dynamicConstants,
      @Nonnull int[] recipeOffsets);

  void onStringSubSequence(
      @Nonnull CharSequence self, int beginIndex, int endIndex, @Nullable CharSequence result);

  void onStringJoin(
      @Nullable CharSequence result,
      @Nonnull CharSequence delimiter,
      @Nonnull CharSequence[] elements);

  void onStringToUpperCase(@Nonnull CharSequence self, @Nullable CharSequence result);

  void onStringToLowerCase(@Nonnull CharSequence self, @Nullable CharSequence result);

  void onStringTrim(@Nonnull CharSequence self, @Nullable CharSequence result);

  void onStringRepeat(@Nonnull CharSequence self, int count, @Nonnull CharSequence result);

  void onStringConstructor(@Nonnull CharSequence self, @Nonnull CharSequence result);

  void onStringFormat(
      @Nonnull String[] literals, @Nonnull Object[] params, @Nonnull CharSequence result);

  void onStringFormat(
      @Nonnull CharSequence pattern, @Nonnull Object[] params, @Nonnull CharSequence result);

  void onStringFormat(
      @Nullable Locale locale,
      @Nonnull CharSequence pattern,
      @Nonnull Object[] params,
      @Nonnull CharSequence result);

  void onSplit(final @Nonnull String self, final @Nonnull String[] result);
}
