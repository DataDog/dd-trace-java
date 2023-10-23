package datadog.trace.bootstrap.debugger.util;

import datadog.trace.api.Config;
import datadog.trace.util.ClassNameTrie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class Redaction {
  // Need to be a unique instance (new String) for reference equality (==) and
  // avoid internalization (intern) by the JVM because it's a string constant
  public static final String REDACTED_VALUE = new String("REDACTED".toCharArray());

  private static final Pattern COMMA_PATTERN = Pattern.compile(",");
  private static final List<String> PREDEFINED_KEYWORDS =
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
          "xrealip");
  private static final Set<String> KEYWORDS = ConcurrentHashMap.newKeySet();
  private static ClassNameTrie typeTrie = ClassNameTrie.Builder.EMPTY_TRIE;
  private static List<String> redactedClasses;
  private static List<String> redactedPackages;

  static {
    /*
     * based on sentry list: https://github.com/getsentry/sentry-python/blob/fefb454287b771ac31db4e30fa459d9be2f977b8/sentry_sdk/scrubber.py#L17-L58
     */
    KEYWORDS.addAll(PREDEFINED_KEYWORDS);
  }

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

  public static void addUserDefinedTypes(Config config) {
    String redactedTypes = config.getDebuggerRedactedTypes();
    if (redactedTypes == null) {
      return;
    }
    List<String> packages = null;
    List<String> classes = null;
    ClassNameTrie.Builder builder = new ClassNameTrie.Builder();
    String[] types = COMMA_PATTERN.split(redactedTypes);
    for (String type : types) {
      builder.put(type, 1);
      if (type.endsWith("*")) {
        if (packages == null) {
          packages = new ArrayList<>();
        }
        type =
            type.endsWith(".*")
                ? type.substring(0, type.length() - 2)
                : type.substring(0, type.length() - 1);
        packages.add(type);
      } else {
        if (classes == null) {
          classes = new ArrayList<>();
        }
        classes.add(type);
      }
    }
    typeTrie = builder.buildTrie();
    redactedPackages = packages;
    redactedClasses = classes;
  }

  public static boolean isRedactedKeyword(String name) {
    if (name == null) {
      return false;
    }
    name = normalize(name);
    return KEYWORDS.contains(name);
  }

  public static boolean isRedactedType(String className) {
    if (className == null) {
      return false;
    }
    return typeTrie.apply(className) > 0;
  }

  public static List<String> getRedactedPackages() {
    return redactedPackages != null ? redactedPackages : Collections.emptyList();
  }

  public static List<String> getRedactedClasses() {
    return redactedClasses != null ? redactedClasses : Collections.emptyList();
  }

  public static void clearUserDefinedTypes() {
    typeTrie = ClassNameTrie.Builder.EMPTY_TRIE;
  }

  public static void resetUserDefinedKeywords() {
    KEYWORDS.clear();
    KEYWORDS.addAll(PREDEFINED_KEYWORDS);
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
