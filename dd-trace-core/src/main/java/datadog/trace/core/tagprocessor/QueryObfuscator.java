package datadog.trace.core.tagprocessor;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QueryObfuscator extends TagsPostProcessor {

  private static final Logger log = LoggerFactory.getLogger(QueryObfuscator.class);

  private static final String REDACTED = "<redacted>";

  @VisibleForTesting
  static final String DEFAULT_OBFUSCATION_PATTERN =
      "(?i)"
          // Sensitive parameter names.
          + "(?:(?:\"|%22)?)"
          + "(?:"
          + "(?:old[-_]?|new[-_]?)?p(?:ass)?w(?:or)?d(?:1|2)?"
          + "|pass(?:[-_]?phrase)?|secret"
          + "|(?:api[-_]?|private[-_]?|public[-_]?|access[-_]?|secret[-_]?|app(?:lication)?[-_]?)"
          + "key(?:[-_]?id)?"
          + "|token|consumer[-_]?(?:id|key|secret)"
          + "|sign(?:ed|ature)?|auth(?:entication|orization)?"
          + ")"
          // Query parameter or quoted JSON value.
          + "(?:"
          + "(?:\\s|%20)*(?:=|%3D)[^&]+"
          + "|(?:\"|%22)(?:\\s|%20)*(?::|%3A)(?:\\s|%20)*(?:\"|%22)"
          + "(?:%2[^2]|%[^2]|[^\"%])+(?:\"|%22)"
          + ")"
          // Tokens and key material, matched independently of parameter names.
          + "|(?:"
          + "bearer(?:\\s|%20)+[a-z0-9._\\-]+"
          + "|token(?::|%3A)[a-z0-9]{13}"
          + "|gh[opsu]_[0-9a-zA-Z]{36}"
          // JWT: capture the preceding delimiter in group 1 for the replacement.
          + "|(^|[^\\w%-]|%[0-9a-f]{2})"
          + "ey[I-L][\\w-]+(?:=|%3D)*\\.ey[I-L][\\w-]+(?:=|%3D)*"
          + "(?:\\.(?:[\\w.+/=-]|%3D|%2F|%2B)+)?"
          // PEM private key.
          + "|-{5}BEGIN(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY-{5}[^\\-]+"
          + "-{5}END(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY(?:-{5})?(?:\\n|%0A)?"
          // SSH public key.
          + "|(?:ssh-(?:rsa|dss)|ecdsa-[a-z0-9]+-[a-z0-9]+)"
          + "(?:\\s|%20|%09)+(?:[a-z0-9/.+]|%2F|%5C|%2B){100,}(?:=|%3D)*"
          + "(?:(?:\\s|%20|%09)+[a-z0-9._-]+)?"
          + ")";

  private final Pattern pattern;
  private final String replacement;
  private final boolean failClosed;

  /**
   * If regex is null - then used default regex pattern If regex is empty string - then disable
   * regex. If the regex does not compile, the query string is not reported.
   */
  public QueryObfuscator(String regex) {
    // empty string -> disabled query obfuscation
    if ("".equals(regex)) {
      this.pattern = null;
      this.replacement = REDACTED;
      this.failClosed = false;
      return;
    }

    // null -> use default regex
    // group references only apply to the default regex, a user regex may capture the secret itself
    if (regex == null) {
      regex = DEFAULT_OBFUSCATION_PATTERN;
      this.replacement = "$1" + REDACTED;
    } else {
      this.replacement = REDACTED;
    }

    Pattern pattern = null;
    boolean failClosed = false;
    try {
      pattern = Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      log.error(
          "Could not compile given query obfuscation regex, the query string will not be reported: {}",
          regex,
          e);
      failClosed = true;
    }
    this.pattern = pattern;
    this.failClosed = failClosed;
  }

  private String obfuscate(String query) {
    if (pattern == null) {
      return query;
    }
    final Matcher matcher = pattern.matcher(query);
    if (!matcher.find()) {
      return query;
    }
    // TODO consider an upstream length cap too
    final StringBuffer sb = new StringBuffer(query.length());
    do {
      matcher.appendReplacement(sb, replacement);
    } while (matcher.find());
    matcher.appendTail(sb);
    return sb.toString();
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    Object query = unsafeTags.getObject(DDTags.HTTP_QUERY);
    if (query instanceof CharSequence) {
      if (failClosed) {
        unsafeTags.remove(DDTags.HTTP_QUERY);
        return;
      }
      try {
        query = obfuscate(query.toString());
      } catch (Exception e) {
        // never report a query that could not be obfuscated, and never log it
        log.debug("Could not obfuscate query string, dropping it", e);
        unsafeTags.remove(DDTags.HTTP_QUERY);
        return;
      }

      unsafeTags.put(DDTags.HTTP_QUERY, query);

      Object url = unsafeTags.getObject(Tags.HTTP_URL);
      if (url instanceof CharSequence) {
        unsafeTags.put(Tags.HTTP_URL, url + "?" + query);
      }
    }
  }
}
