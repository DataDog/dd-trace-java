package com.datadog.iast.sensitive;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.util.Ranged;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface SensitiveHandler {

  static SensitiveHandler get() {
    return SensitiveHandlerImpl.INSTANCE;
  }

  boolean isSensitiveName(@Nullable String name);

  boolean isSensitiveValue(@Nullable String value);

  String redactSource(@Nonnull Source source);

  String redactString(String value);

  Tokenizer tokenizeEvidence(@Nonnull VulnerabilityType type, @Nonnull final Evidence evidence);

  default boolean isSensitive(@Nullable final Source source) {
    return source != null
        && (isSensitiveName(source.getName()) || isSensitiveValue(source.getValue()));
  }

  interface Tokenizer {

    boolean next();

    Ranged current();

    Tokenizer EMPTY =
        new Tokenizer() {

          @Override
          public boolean next() {
            return false;
          }

          @Override
          public Ranged current() {
            throw new NoSuchElementException("Tokenizer is empty");
          }
        };
  }
}
