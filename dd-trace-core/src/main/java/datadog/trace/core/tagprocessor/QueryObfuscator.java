package datadog.trace.core.tagprocessor;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.OtelHttpSemantics;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QueryObfuscator extends TagsPostProcessor {

  private static final Logger log = LoggerFactory.getLogger(QueryObfuscator.class);

  private static final String REDACTED = "<redacted>";

  // Every match of this pattern must contain one of the literals mayMatchDefaultPattern looks for.
  // When changing it, update that method; QueryObfuscatorDefaultPatternTest pins the pattern so a
  // change can't go unnoticed.
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
  private final boolean defaultPattern;
  private final boolean otelSemanticsEnabled;

  /**
   * If regex is null - then used default regex pattern If regex is empty string - then disable
   * regex. If the regex does not compile, the query string is not reported.
   */
  public QueryObfuscator(String regex) {
    this(regex, Config.get().isTraceOtelSemanticsEnabled());
  }

  QueryObfuscator(String regex, boolean otelSemanticsEnabled) {
    this.otelSemanticsEnabled = otelSemanticsEnabled;
    // empty string -> disabled query obfuscation
    if ("".equals(regex)) {
      this.pattern = null;
      this.replacement = REDACTED;
      this.failClosed = false;
      this.defaultPattern = false;
      return;
    }

    // null -> use default regex
    // group references only apply to the default regex, a user regex may capture the secret itself
    this.defaultPattern = regex == null;
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
    if (pattern == null || (defaultPattern && !mayMatchDefaultPattern(query))) {
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
    processQuery(unsafeTags, spanContext, DDTags.HTTP_QUERY);
    if (otelSemanticsEnabled) {
      processQuery(unsafeTags, spanContext, KnownTags.URL_QUERY_NAME);
    }
  }

  private void processQuery(TagMap unsafeTags, DDSpanContext spanContext, String queryTag) {
    Object query = unsafeTags.getObject(queryTag);
    if (query instanceof CharSequence) {
      if (failClosed) {
        unsafeTags.remove(queryTag);
        return;
      }
      final String obfuscatedQuery;
      try {
        obfuscatedQuery = obfuscate(query.toString());
      } catch (Exception e) {
        // never report a query that could not be obfuscated, and never log it
        log.debug("Could not obfuscate query string, dropping it", e);
        unsafeTags.remove(queryTag);
        return;
      }

      unsafeTags.put(queryTag, obfuscatedQuery);

      Object url = unsafeTags.getObject(Tags.HTTP_URL);
      if (url instanceof CharSequence) {
        if (otelSemanticsEnabled) {
          unsafeTags.put(Tags.HTTP_URL, rebuildOtelUrl(url.toString(), obfuscatedQuery));
        } else {
          unsafeTags.put(Tags.HTTP_URL, url + "?" + obfuscatedQuery);
        }
      }

      // HTTP clients carry the query separately only so this processor can scrub it before
      // rebuilding url.full. url.query is a server attribute in the OTel HTTP conventions.
      if (otelSemanticsEnabled
          && spanContext != null
          && spanContext.getSpanType() != null
          && DDSpanTypes.HTTP_CLIENT.contentEquals(spanContext.getSpanType())) {
        unsafeTags.remove(queryTag);
      }
    }
  }

  /**
   * Tells whether {@link #DEFAULT_OBFUSCATION_PATTERN} could find a match in the text. False means
   * it certainly finds none; true means it may. Every match of the pattern contains one of these
   * literals, ignoring case as the pattern's {@code (?i)} does: {@code pw}, {@code pass}, {@code
   * secret}, {@code key}, {@code token}, {@code consumer}, {@code sign}, {@code auth}, {@code
   * bearer}, {@code gh[opsu]_}, {@code ey[i-l]}, {@code -----begin}, {@code ssh-} or {@code
   * ecdsa-}. Keep this in step with the pattern.
   */
  @VisibleForTesting
  static boolean mayMatchDefaultPattern(final String text) {
    final int n = text.length();
    for (int i = 0; i < n; i++) {
      switch (fold(text.charAt(i))) {
        case 'p':
          if (startsWith(text, i, "pw") || startsWith(text, i, "pass")) {
            return true;
          }
          break;
        case 's':
          if (startsWith(text, i, "secret")
              || startsWith(text, i, "sign")
              || startsWith(text, i, "ssh-")) {
            return true;
          }
          break;
        case 'k':
          if (startsWith(text, i, "key")) {
            return true;
          }
          break;
        case 't':
          if (startsWith(text, i, "token")) {
            return true;
          }
          break;
        case 'c':
          if (startsWith(text, i, "consumer")) {
            return true;
          }
          break;
        case 'a':
          if (startsWith(text, i, "auth")) {
            return true;
          }
          break;
        case 'b':
          if (startsWith(text, i, "bearer")) {
            return true;
          }
          break;
        case 'g':
          if (i + 3 < n
              && fold(text.charAt(i + 1)) == 'h'
              && "opsu".indexOf(fold(text.charAt(i + 2))) >= 0
              && text.charAt(i + 3) == '_') {
            return true;
          }
          break;
        case 'e':
          if ((i + 2 < n
                  && fold(text.charAt(i + 1)) == 'y'
                  && "ijkl".indexOf(fold(text.charAt(i + 2))) >= 0)
              || startsWith(text, i, "ecdsa-")) {
            return true;
          }
          break;
        case '-':
          if (startsWith(text, i, "-----begin")) {
            return true;
          }
          break;
        default:
          break;
      }
    }
    return false;
  }

  /** Whether the text has the lower-case ASCII literal at the index, ignoring case as re2j does. */
  private static boolean startsWith(final String text, final int at, final String literal) {
    final int len = literal.length();
    if (at + len > text.length()) {
      return false;
    }
    for (int j = 0; j < len; j++) {
      if (fold(text.charAt(at + j)) != literal.charAt(j)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Folds a character the way re2j's {@code (?i)} relates it to ASCII letters: upper-case ASCII to
   * lower-case, and the two non-ASCII characters that fold to ASCII letters, the Kelvin sign to
   * {@code k} and the long s to {@code s}.
   */
  private static char fold(final char c) {
    if (c >= 'A' && c <= 'Z') {
      return (char) (c + ('a' - 'A'));
    } else if (c == '\u212A') {
      return 'k';
    } else if (c == '\u017F') {
      return 's';
    }
    return c;
  }

  private String rebuildOtelUrl(final String url, final String query) {
    final String withoutQuery = OtelHttpSemantics.withoutQuery(url);
    final int fragment = withoutQuery.indexOf('#');
    if (fragment < 0) {
      return withoutQuery + "?" + query;
    }
    return withoutQuery.substring(0, fragment) + "?" + query + withoutQuery.substring(fragment);
  }
}
