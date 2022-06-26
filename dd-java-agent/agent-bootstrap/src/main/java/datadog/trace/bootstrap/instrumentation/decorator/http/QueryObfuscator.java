package datadog.trace.bootstrap.instrumentation.decorator.http;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.util.Strings;

public class QueryObfuscator {

  private static final String DEFAULT_OBFUSCATION_PATTERN =
      "((?i)(?:p(?:ass)?w(?:or)?d|pass(?:_?phrase)?|secret|(?:api_?|private_?|public_?|access_?|secret_?)key(?:_?id)?|token|consumer_?(?:id|key|secret)|sign(?:ed|ature)?|auth(?:entication|orization)?)(?:(?:\\s|%20)*(?:=|%3D)[^&]+|(?:\"|%22)(?:\\s|%20)*(?::|%3A)(?:\\s|%20)*(?:\"|%22)(?:%2[^2]|%[^2]|[^\"%])+(?:\"|%22))|bearer(?:\\s|%20)+[a-z0-9\\._\\-]|token(?::|%3A)[a-z0-9]{13}|gh[opsu]_[0-9a-zA-Z]{36}|ey[I-L](?:[\\w=-]|%3D)+\\.ey[I-L](?:[\\w=-]|%3D)+(?:\\.(?:[\\w.+\\/=-]|%3D|%2F|%2B)+)?|[\\-]{5}BEGIN(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY[\\-]{5}[^\\-]+[\\-]{5}END(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY|ssh-rsa(?:\\s|%20)*(?:[a-z0-9\\/\\.+]|%2F|%5C|%2B){100,})";

  public static Pattern pattern;
  public static String lastRegex;

  public static String obfuscate(String query, String regex) {
    // Empty regex - means obfuscation deactivated
    if (query == null || "".equals(regex)) {
      return query;
    }

    // if encoded
    query = URIUtils.decode(query);
    if (query.isEmpty()) {
      return "/";
    }

    // Use default if regex is undefined
    if (regex == null) {
      regex = DEFAULT_OBFUSCATION_PATTERN;
    }

    if (!regex.equals(lastRegex)) {
      pattern = Pattern.compile(regex);
      lastRegex = regex;
    }

    Matcher matcher = pattern.matcher(query);
    while (matcher.find()) {
      query = Strings.replace(query, matcher.group(), "<redacted>");
    }
    return query;
  }
}
