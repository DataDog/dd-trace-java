package com.datadog.iast.sensitive;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.MULTILINE;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SensitiveHandlerImpl implements SensitiveHandler {

  static final SensitiveHandler INSTANCE = new SensitiveHandlerImpl();

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
  public Tokenizer tokenizeEvidence(
      @Nonnull final VulnerabilityType type, @Nonnull final Evidence evidence) {
    final TokenizerSupplier supplier = tokenizers.computeIfAbsent(type, t -> emptyTokenizer());
    return supplier.tokenizerFor(evidence);
  }

  private TokenizerSupplier emptyTokenizer() {
    return evidence -> Tokenizer.EMPTY;
  }

  private interface TokenizerSupplier {
    Tokenizer tokenizerFor(Evidence evidence);
  }
}
