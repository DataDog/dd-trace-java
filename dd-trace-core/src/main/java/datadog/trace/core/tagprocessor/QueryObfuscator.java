package datadog.trace.core.tagprocessor;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QueryObfuscator extends TagsPostProcessor {

  private static final Logger log = LoggerFactory.getLogger(QueryObfuscator.class);

  private static final String DEFAULT_OBFUSCATION_PATTERN =
      "(?i)(?:(?:\"|%22)?)(?:(?:old[-_]?|new[-_]?)?p(?:ass)?w(?:or)?d(?:1|2)?|pass(?:[-_]?phrase)?|secret|(?:api[-_]?|private[-_]?|public[-_]?|access[-_]?|secret[-_]?|app(?:lication)?[-_]?)key(?:[-_]?id)?|token|consumer[-_]?(?:id|key|secret)|sign(?:ed|ature)?|auth(?:entication|orization)?)(?:(?:\\s|%20)*(?:=|%3D)[^&]+|(?:\"|%22)(?:\\s|%20)*(?::|%3A)(?:\\s|%20)*(?:\"|%22)(?:%2[^2]|%[^2]|[^\"%])+(?:\"|%22))|(?:bearer(?:\\s|%20)+[a-z0-9._\\-]+|token(?::|%3A)[a-z0-9]{13}|gh[opsu]_[0-9a-zA-Z]{36}|ey[I-L](?:[\\w=-]|%3D)+\\.ey[I-L](?:[\\w=-]|%3D)+(?:\\.(?:[\\w.+/=-]|%3D|%2F|%2B)+)?|-{5}BEGIN(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY-{5}[^\\-]+-{5}END(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY(?:-{5})?(?:\\n|%0A)?|(?:ssh-(?:rsa|dss)|ecdsa-[a-z0-9]+-[a-z0-9]+)(?:\\s|%20|%09)+(?:[a-z0-9/.+]|%2F|%5C|%2B){100,}(?:=|%3D)*(?:(?:\\s|%20|%09)+[a-z0-9._-]+)?)";

  private final Pattern pattern;
  private final boolean otelSemanticsEnabled;

  /**
   * If regex is null - then used default regex pattern If regex is empty string - then disable
   * regex
   */
  public QueryObfuscator(String regex) {
    this(regex, Config.get().isTraceOtelSemanticsEnabled());
  }

  QueryObfuscator(String regex, boolean otelSemanticsEnabled) {
    this.otelSemanticsEnabled = otelSemanticsEnabled;
    // empty string -> disabled query obfuscation
    if ("".equals(regex)) {
      this.pattern = null;
      return;
    }

    // null -> use default regex
    if (regex == null) {
      regex = DEFAULT_OBFUSCATION_PATTERN;
    }

    Pattern pattern = null;
    try {
      pattern = Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      log.error("Could not compile given query obfuscation regex: {}", regex, e);
    }
    this.pattern = pattern;
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
      matcher.appendReplacement(sb, "<redacted>");
    } while (matcher.find());
    matcher.appendTail(sb);
    return sb.toString();
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    String queryTag = otelSemanticsEnabled ? KnownTags.URL_QUERY_NAME : DDTags.HTTP_QUERY;
    Object query = unsafeTags.getObject(queryTag);
    if (query instanceof CharSequence) {
      query = obfuscate(query.toString());

      unsafeTags.put(queryTag, query);

      Object url = unsafeTags.getObject(Tags.HTTP_URL);
      if (url instanceof CharSequence) {
        unsafeTags.put(Tags.HTTP_URL, appendQuery(url.toString(), query.toString()));
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

  private static String appendQuery(final String url, final String query) {
    final int fragment = url.indexOf('#');
    if (fragment < 0) {
      return url + "?" + query;
    }
    return new StringBuilder(url.length() + query.length() + 1)
        .append(url, 0, fragment)
        .append('?')
        .append(query)
        .append(url, fragment, url.length())
        .toString();
  }
}
