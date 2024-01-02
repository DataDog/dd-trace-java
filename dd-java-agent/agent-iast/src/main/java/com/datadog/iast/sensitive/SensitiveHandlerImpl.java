package com.datadog.iast.sensitive;

import static com.datadog.iast.util.CharUtils.fillCharArray;
import static com.datadog.iast.util.CharUtils.newCharArray;
import static com.datadog.iast.util.CharUtils.newString;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.MULTILINE;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SensitiveHandlerImpl implements SensitiveHandler {

  static final SensitiveHandler INSTANCE = new SensitiveHandlerImpl();

  private static final char[] REDACTED_SENSITIVE_BUFFER = newCharArray(16, '*');
  private static final char[] REDACTED_SOURCE_BUFFER = new char[62];

  static {
    int offset = 0;
    offset += fillCharArray(offset, 'a', 'z', REDACTED_SOURCE_BUFFER);
    offset += fillCharArray(offset, 'A', 'Z', REDACTED_SOURCE_BUFFER);
    fillCharArray(offset, '0', '9', REDACTED_SOURCE_BUFFER);
  }

  private final Pattern namePattern;
  private final Pattern valuePattern;
  private final Map<VulnerabilityType, TokenizerSupplier> tokenizers;

  public SensitiveHandlerImpl() {
    final Config config = Config.get();
    namePattern = Pattern.compile(config.getIastRedactionNamePattern(), CASE_INSENSITIVE);
    valuePattern =
        Pattern.compile(config.getIastRedactionValuePattern(), CASE_INSENSITIVE | MULTILINE);
    tokenizers = new HashMap<>();
    tokenizers.put(VulnerabilityType.SQL_INJECTION, SqlRegexpTokenizer::new);
    tokenizers.put(VulnerabilityType.LDAP_INJECTION, LdapRegexTokenizer::new);
    tokenizers.put(VulnerabilityType.COMMAND_INJECTION, CommandRegexpTokenizer::new);
    tokenizers.put(VulnerabilityType.SSRF, UrlRegexpTokenizer::new);
    tokenizers.put(VulnerabilityType.UNVALIDATED_REDIRECT, UrlRegexpTokenizer::new);
    tokenizers.put(VulnerabilityType.XSS, TaintedRangeBasedTokenizer::new);
    tokenizers.put(VulnerabilityType.HEADER_INJECTION, TaintedRangeBasedTokenizer::new);
  }

  @Override
  public boolean isSensitiveName(@Nullable final String name) {
    return name != null && namePattern.matcher(name).find();
  }

  @Override
  public boolean isSensitiveValue(@Nullable final String value) {
    return value != null && valuePattern.matcher(value).find();
  }

  @Override
  public String redactSource(@Nonnull final Source source) {
    final String value = source.getValue();
    return newString(computeLength(value), REDACTED_SOURCE_BUFFER);
  }

  @Override
  public String redactString(final String value) {
    return newString(computeLength(value), REDACTED_SENSITIVE_BUFFER);
  }

  @Override
  public Tokenizer tokenizeEvidence(
      @Nonnull final VulnerabilityType type, @Nonnull final Evidence evidence) {
    final TokenizerSupplier supplier = tokenizers.computeIfAbsent(type, t -> emptyTokenizer());
    return supplier.tokenizerFor(evidence);
  }

  private int computeLength(@Nullable final String value) {
    if (value == null || value.isEmpty()) {
      return 0;
    }
    int size = 0;
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (!Character.isHighSurrogate(c)) {
        size++;
      }
    }
    return size;
  }

  private TokenizerSupplier emptyTokenizer() {
    return evidence -> Tokenizer.EMPTY;
  }

  private interface TokenizerSupplier {
    Tokenizer tokenizerFor(Evidence evidence);
  }
}
