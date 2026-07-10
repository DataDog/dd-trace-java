package com.datadog.iast.sensitive;

import static com.datadog.iast.util.CharUtils.fillCharArray;
import static com.datadog.iast.util.CharUtils.newCharArray;
import static com.datadog.iast.util.CharUtils.newString;
import static com.google.re2j.Pattern.CASE_INSENSITIVE;
import static com.google.re2j.Pattern.MULTILINE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_REDACTION_NAME_PATTERN;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_REDACTION_VALUE_PATTERN;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.VulnerabilityType;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SensitiveHandlerImpl implements SensitiveHandler {

  private static final Logger LOG = LoggerFactory.getLogger(SensitiveHandlerImpl.class);

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
    this(Config.get().getIastRedactionNamePattern(), Config.get().getIastRedactionValuePattern());
  }

  SensitiveHandlerImpl(final String configuredNamePattern, final String configuredValuePattern) {
    namePattern =
        safeCompile(configuredNamePattern, DEFAULT_IAST_REDACTION_NAME_PATTERN, CASE_INSENSITIVE);
    valuePattern =
        safeCompile(
            configuredValuePattern,
            DEFAULT_IAST_REDACTION_VALUE_PATTERN,
            CASE_INSENSITIVE | MULTILINE);
    tokenizers = new HashMap<>();
    tokenizers.put(VulnerabilityType.SQL_INJECTION, SqlRegexpTokenizer::new);
    tokenizers.put(VulnerabilityType.LDAP_INJECTION, LdapRegexTokenizer::new);
    tokenizers.put(VulnerabilityType.COMMAND_INJECTION, CommandRegexpTokenizer::new);
    tokenizers.put(VulnerabilityType.SSRF, UrlRegexpTokenizer::new);
    tokenizers.put(VulnerabilityType.UNVALIDATED_REDIRECT, UrlRegexpTokenizer::new);
    tokenizers.put(VulnerabilityType.XSS, TaintedRangeBasedTokenizer::new);
    tokenizers.put(
        VulnerabilityType.HEADER_INJECTION,
        evidence -> new HeaderRegexpTokenizer(evidence, namePattern, valuePattern));
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
    final TokenizerSupplier supplier = tokenizers.get(type);
    return supplier == null ? Tokenizer.EMPTY : supplier.tokenizerFor(evidence);
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

  private static Pattern safeCompile(
      final String configured, final String fallback, final int flags) {
    try {
      return Pattern.compile(configured, flags);
    } catch (final PatternSyntaxException e) {
      LOG.error(
          "Could not compile IAST redaction pattern with RE2J, falling back to the default: {} (configured: {})",
          fallback,
          configured,
          e);
      return Pattern.compile(fallback, flags);
    }
  }

  private interface TokenizerSupplier {
    Tokenizer tokenizerFor(Evidence evidence);
  }
}
