package com.datadog.iast.sensitive;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.util.Ranged;
import com.google.re2j.Pattern;

/**
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1738>Uniform Resource Locators (URL)</a>
 */
public class UrlRegexpTokenizer extends AbstractRegexTokenizer {

  private static final String AUTHORITY_GROUP = "AUTHORITY";
  private static final String QUERY_FRAGMENT_GROUP = "QUERY";

  private static final String AUTHORITY =
      String.format("^(?:[^:]+:)?//(?P<%s>[^@]+)@", AUTHORITY_GROUP);
  private static final String QUERY_FRAGMENT =
      String.format("[?#&]([^=&;]+)=(?P<%s>[^?#&]+)", QUERY_FRAGMENT_GROUP);

  private static final Pattern PATTERN =
      Pattern.compile(String.join("|", AUTHORITY, QUERY_FRAGMENT));

  protected UrlRegexpTokenizer(final Evidence evidence) {
    super(PATTERN, evidence.getValue());
  }

  @Override
  protected Ranged buildNext() {
    final String group =
        matcher.group(AUTHORITY_GROUP) != null ? AUTHORITY_GROUP : QUERY_FRAGMENT_GROUP;
    final int start = matcher.start(group);
    return Ranged.build(start, matcher.end(group) - start);
  }
}
