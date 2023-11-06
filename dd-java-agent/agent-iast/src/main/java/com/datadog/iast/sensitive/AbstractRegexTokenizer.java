package com.datadog.iast.sensitive;

import com.datadog.iast.util.Ranged;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

public abstract class AbstractRegexTokenizer implements SensitiveHandler.Tokenizer {

  protected final Matcher matcher;
  @Nullable private Ranged current;

  protected AbstractRegexTokenizer(final Pattern pattern, final String evidence) {
    matcher = pattern.matcher(evidence);
  }

  @Override
  public final boolean next() {
    final boolean hasNext = matcher.find();
    current = hasNext ? buildNext() : null;
    return hasNext;
  }

  @Override
  public final Ranged current() {
    if (current == null) {
      throw new NoSuchElementException();
    }
    return current;
  }

  protected abstract Ranged buildNext();
}
