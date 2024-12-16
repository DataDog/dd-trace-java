package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface StringModule extends IastModule {

  void onStringConcat(@Nonnull String left, @Nullable String right, @Nonnull String result);

  void onStringBuilderInit(@Nonnull CharSequence builder, @Nullable CharSequence param);

  void onStringBuilderAppend(@Nonnull CharSequence builder, @Nullable CharSequence param);

  void onStringBuilderAppend(
      @Nonnull CharSequence builder, @Nullable CharSequence param, int start, int end);

  void onStringBuilderToString(@Nonnull CharSequence builder, @Nonnull String result);

  void onStringConcatFactory(
      @Nullable String result,
      @Nullable String[] args,
      @Nullable String recipe,
      @Nullable Object[] dynamicConstants,
      @Nonnull int[] recipeOffsets);

  void onStringSubSequence(
      @Nonnull CharSequence self, int beginIndex, int endIndex, @Nullable CharSequence result);

  void onStringJoin(
      @Nullable String result, @Nonnull CharSequence delimiter, @Nonnull CharSequence[] elements);

  void onStringToUpperCase(@Nonnull String self, @Nullable String result);

  void onStringToLowerCase(@Nonnull String self, @Nullable String result);

  void onStringTrim(@Nonnull String self, @Nullable String result);

  void onStringRepeat(@Nonnull String self, int count, @Nonnull String result);

  void onStringConstructor(@Nonnull CharSequence self, @Nonnull String result);

  void onStringFormat(@Nonnull String pattern, @Nonnull Object[] params, @Nonnull String result);

  void onStringFormat(
      @Nullable Locale locale,
      @Nonnull String pattern,
      @Nonnull Object[] params,
      @Nonnull String result);

  void onStringFormat(
      @Nonnull Iterable<String> literals, @Nonnull Object[] params, @Nonnull String result);

  void onSplit(final @Nonnull String self, final @Nonnull String[] result);

  void onStringStrip(@Nonnull String self, @Nonnull String result, boolean trailing);

  void onIndent(@Nonnull String self, int indentation, @Nonnull String result);

  void onStringReplace(@Nonnull String self, char oldChar, char newChar, @Nonnull String result);

  String onStringReplace(@Nonnull String self, CharSequence oldCharSeq, CharSequence newCharSeq);

  String onStringReplace(
      @Nonnull String self, String regex, String replacement, int numReplacements);

  void onStringValueOf(Object param, @Nullable String result);
}
