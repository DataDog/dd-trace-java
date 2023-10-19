package datadog.trace.bootstrap.debugger.util;

import datadog.trace.api.Config;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class Redaction {
  // Need to be a unique instance (new String) for reference equality (==) and
  // avoid internalization (intern) by the JVM because it's a string constant
  public static final String REDACTED_VALUE = new String("REDACTED".toCharArray());

  /*
   * based on sentry list: https://github.com/getsentry/sentry-python/blob/fefb454287b771ac31db4e30fa459d9be2f977b8/sentry_sdk/scrubber.py#L17-L58
   */
  private static final Set<String> KEYWORDS = ConcurrentHashMap.newKeySet();

  static {
    KEYWORDS.addAll(
        Arrays.asList(
            "password",
            "passwd",
            "secret",
            "apikey",
            "auth",
            "credentials",
            "mysqlpwd",
            "privatekey",
            "token",
            "ipaddress",
            "session",
            // django
            "csrftoken",
            "sessionid",
            // wsgi
            "remoteaddr",
            "xcsrftoken",
            "xforwardedfor",
            "setcookie",
            "cookie",
            "authorization",
            "xapikey",
            "xforwardedfor",
            "xrealip"));
  }

  private static final Pattern COMMA_PATTERN = Pattern.compile(",");

  public static void addUserDefinedKeywords(Config config) {
    String redactedIdentifiers = config.getDebuggerRedactedIdentifiers();
    if (redactedIdentifiers == null) {
      return;
    }
    String[] identifiers = COMMA_PATTERN.split(redactedIdentifiers);
    for (String identifier : identifiers) {
      KEYWORDS.add(normalize(identifier));
    }
  }

  public static boolean isRedactedKeyword(String name) {
    if (name == null) {
      return false;
    }
    name = normalize(name);
    return KEYWORDS.contains(name);
  }

  private static String normalize(String name) {
    StringBuilder sb = null;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean isUpper = Character.isUpperCase(c);
      boolean isRemovable = isRemovableChar(c);
      if (isUpper || isRemovable || sb != null) {
        if (sb == null) {
          sb = new StringBuilder(name.substring(0, i));
        }
        if (isUpper) {
          sb.append(Character.toLowerCase(c));
        } else if (!isRemovable) {
          sb.append(c);
        }
      }
    }
    return sb != null ? sb.toString() : name;
  }

  private static boolean isRemovableChar(char c) {
    return c == '_' || c == '-' || c == '$' || c == '@';
  }
}
