package com.datadog.iast.sensitive;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.util.Ranged;
import java.util.regex.Pattern;

/**
 * @see <a href="https://docs.ldap.com/specs/rfc4515.txt">Lightweight Directory Access Protocol
 *     (LDAP): String Representation of Search Filters</a>
 */
public class LdapRegexTokenizer extends AbstractRegexTokenizer {

  private static final String LITERAL_GROUP = "LITERAL";

  private static final Pattern LDAP_PATTERN =
      Pattern.compile(
          String.format("\\(.*?(?:~=|=|<=|>=)(?<%s>[^)]+)\\)", LITERAL_GROUP), Pattern.MULTILINE);

  public LdapRegexTokenizer(final Evidence evidence) {
    super(LDAP_PATTERN, evidence.getValue());
  }

  @Override
  protected Ranged buildNext() {
    final int start = matcher.start(LITERAL_GROUP);
    return Ranged.build(start, matcher.end(LITERAL_GROUP) - start);
  }
}
