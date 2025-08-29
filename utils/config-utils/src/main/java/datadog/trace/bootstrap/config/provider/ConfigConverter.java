package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.ConfigStrings.normalizedHeaderTag;
import static datadog.trace.util.ConfigStrings.trim;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ConfigConverter {

  private static final Logger log = LoggerFactory.getLogger(ConfigConverter.class);

  /**
   * Custom exception for invalid boolean values to maintain backward compatibility. When caught in
   * ConfigProvider, boolean methods should return false instead of default value.
   */
  static class InvalidBooleanValueException extends IllegalArgumentException {
    public InvalidBooleanValueException(String message) {
      super(message);
    }
  }

  private static final ValueOfLookup LOOKUP = new ValueOfLookup();

  /**
   * @param value to parse by tClass::valueOf
   * @param tClass should contain static parsing method "T valueOf(String)"
   * @param <T>
   * @return value == null || value.trim().isEmpty() ? defaultValue : tClass.valueOf(value)
   * @throws NumberFormatException
   */
  static <T> T valueOf(final String value, @Nonnull final Class<T> tClass) {
    Objects.requireNonNull(tClass, "tClass is marked non-null but is null");
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    try {
      return (T) LOOKUP.get(tClass).invoke(value);
    } catch (final NumberFormatException e) {
      throw e;
    } catch (final IllegalArgumentException e) {
      throw e;
    } catch (final Throwable e) {
      log.debug("Can't parse: ", e);
      throw new NumberFormatException(e.toString());
    }
  }

  @Nonnull
  static List<String> parseList(final String str) {
    return parseList(str, ",");
  }

  @Nonnull
  @SuppressForbidden
  static List<String> parseList(final String str, final String separator) {
    String trimmed = trim(str);
    if (trimmed.isEmpty()) {
      return Collections.emptyList();
    }

    final String[] tokens = trimmed.split(separator, -1);
    // Remove whitespace from each item.
    for (int i = 0; i < tokens.length; i++) {
      tokens[i] = tokens[i].trim();
    }
    return Collections.unmodifiableList(Arrays.asList(tokens));
  }

  @Nonnull
  static Map<String, String> parseMap(final String str, final String settingName) {
    return parseMap(str, settingName, ':');
  }

  @Nonnull
  static Map<String, String> parseMap(
      final String str, final String settingName, final char keyValueSeparator) {
    // If we ever want to have default values besides an empty map, this will need to change.
    String trimmed = trim(str);
    if (trimmed.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, String> map = new HashMap<>();
    loadMap(map, trimmed, settingName, keyValueSeparator);
    return map;
  }

  @Nonnull
  static Map<String, String> parseTraceTagsMap(
      final String str, final char keyValueSeparator, final List<Character> argSeparators) {
    // If we ever want to have default values besides an empty map, this will need to change.
    String trimmed = trim(str);
    if (trimmed.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, String> map = new HashMap<>();
    loadTraceTagsMap(map, trimmed, keyValueSeparator, argSeparators);
    return map;
  }

  /**
   * This parses a mixed map that can have both key value pairs, and also keys only, that will get
   * values on the form "defaultPrefix.key". For keys without a value, the corresponding value will
   * be normalized by converting the key to lower case and replacing all non alphanumeric
   * characters, except '_', '-', '/' with '_'.
   *
   * <p>The allowed format is "(key:value|key)([ ,](key:value|key))*", where you have to choose
   * between ',' or ' ' as the separator.
   *
   * @param str String to parse
   * @param settingName Name of the setting being parsed
   * @param defaultPrefix Default prefix to add to key ony items
   * @param lowercaseKeys Should the keys be converted to lowercase
   * @return A map containing the parsed key value pairs
   */
  @Nonnull
  static Map<String, String> parseMapWithOptionalMappings(
      final String str,
      final String settingName,
      final String defaultPrefix,
      boolean lowercaseKeys) {
    String trimmed = trim(str);
    if (trimmed.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, String> map = new HashMap<>();
    loadMapWithOptionalMapping(map, trimmed, settingName, defaultPrefix, lowercaseKeys);
    return map;
  }

  @Nonnull
  static Map<String, String> parseOrderedMap(final String str, final String settingName) {
    // If we ever want to have default values besides an empty map, this will need to change.
    String trimmed = trim(str);
    if (trimmed.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, String> map = new LinkedHashMap<>();
    loadMap(map, trimmed, settingName, ':');
    return map;
  }

  private static final class BadFormatException extends Exception {
    public BadFormatException(String message) {
      super(message);
    }
  }

  private static void loadMap(
      Map<String, String> map, String str, String settingName, char keyValueSeparator) {
    // we know that the str is trimmed and rely on that there is no leading/trailing whitespace
    try {
      int start = 0;
      int splitter = str.indexOf(keyValueSeparator, start);
      while (splitter != -1) {
        int nextSplitter = str.indexOf(keyValueSeparator, splitter + 1);
        int nextComma = str.indexOf(',', splitter + 1);
        nextComma = nextComma == -1 ? str.length() : nextComma;
        int nextSpace = str.indexOf(' ', splitter + 1);
        nextSpace = nextSpace == -1 ? str.length() : nextSpace;
        // if we have a delimiter after this splitter, then try to move the splitter forward to
        // allow for tags with ':' in them
        int end = nextComma < str.length() ? nextComma : nextSpace;
        while (nextSplitter != -1 && nextSplitter < end) {
          nextSplitter = str.indexOf(keyValueSeparator, nextSplitter + 1);
        }
        if (nextSplitter == -1) {
          // this is either the end of the string or the next position where the value should be
          // trimmed
          end = nextComma;
          if (nextComma < str.length() - 1) {
            // there are non-space characters after the ','
            throw new BadFormatException("Non white space characters after trailing ','");
          }
        } else {
          if (nextComma < str.length()) {
            end = nextComma;
          } else if (nextSpace < str.length()) {
            end = nextSpace;
          } else {
            // this should not happen
            throw new BadFormatException("Illegal position of split character ':'");
          }
        }
        String key = str.substring(start, splitter).trim();
        if (key.indexOf(',') != -1) {
          throw new BadFormatException("Illegal ',' character in key '" + key + "'");
        }
        String value = str.substring(splitter + 1, end).trim();
        if (value.indexOf(' ') != -1) {
          throw new BadFormatException("Illegal ' ' character in value for key '" + key + "'");
        }
        if (!key.isEmpty() && !value.isEmpty()) {
          map.put(key, value);
        }
        splitter = nextSplitter;
        start = end + 1;
      }
    } catch (Throwable t) {
      if (t instanceof BadFormatException) {
        log.warn(
            "Invalid config for {}. {}. Must match "
                + "'key1{}value1,key2{}value2' or "
                + "'key1{}value1 key2{}value2'.",
            settingName,
            t.getMessage(),
            keyValueSeparator,
            keyValueSeparator,
            keyValueSeparator,
            keyValueSeparator);
      } else {
        log.warn("Unexpected exception during config parsing of {}.", settingName, t);
      }
      map.clear();
    }
  }

  private static void loadTraceTagsMap(
      Map<String, String> map,
      String str,
      char keyValueSeparator,
      final List<Character> argSeparators) {
    int start = 0;
    int splitter = str.indexOf(keyValueSeparator, start);
    char argSeparator = '\0';
    int argSeparatorInd = -1;

    // Given a list of separators ordered by priority, find the first (highest priority) separator
    // that appears in the string and store its value and first occurrence in the string
    for (Character sep : argSeparators) {
      argSeparatorInd = str.indexOf(sep);
      if (argSeparatorInd != -1) {
        argSeparator = sep;
        break;
      }
    }
    while (start < str.length()) {
      int nextSplitter =
          argSeparatorInd == -1
              ? -1
              : str.indexOf(
                  keyValueSeparator,
                  argSeparatorInd + 1); // next splitter after the next argSeparator
      int nextArgSeparator =
          argSeparatorInd == -1 ? -1 : str.indexOf(argSeparator, argSeparatorInd + 1);
      int end = argSeparatorInd == -1 ? str.length() : argSeparatorInd;

      if (start >= end) { // the character is only the delimiter
        start = end + 1;
        splitter = nextSplitter;
        argSeparatorInd = nextArgSeparator;
        continue;
      }

      String key, value;
      if (splitter >= end
          || splitter
              == -1) { // only key, no value; either due end of string or substring not having
        // splitter
        key = str.substring(start, end).trim();
        value = "";
      } else {
        key = str.substring(start, splitter).trim();
        value = str.substring(splitter + 1, end).trim();
      }
      if (!key.isEmpty()) {
        map.put(key, value);
      }
      splitter = nextSplitter;
      argSeparatorInd = nextArgSeparator;
      start = end + 1;
    }
  }

  private static void loadMapWithOptionalMapping(
      Map<String, String> map,
      String str,
      String settingName,
      String defaultPrefix,
      boolean lowercaseKeys) {
    try {
      defaultPrefix = null == defaultPrefix ? "" : defaultPrefix;
      if (!defaultPrefix.isEmpty() && !defaultPrefix.endsWith(".")) {
        defaultPrefix = defaultPrefix + ".";
      }
      int start = 0;
      int len = str.length();
      char listChar = str.indexOf(',') == -1 ? ' ' : ',';
      while (start < len) {
        int end = len;
        int listPos = str.indexOf(listChar, start);
        int mapPos = str.indexOf(':', start);
        int delimiter = listPos == -1 ? mapPos : mapPos == -1 ? listPos : Math.min(listPos, mapPos);
        if (delimiter == -1) {
          delimiter = end;
        } else if (delimiter == mapPos) {
          // we're in a mapping, so let's find the next part
          int nextList = str.indexOf(listChar, delimiter + 1);
          if (mapPos == start) {
            // can't have an empty key
            throw new BadFormatException("Illegal empty key at position " + start);
          } else if (nextList != -1) {
            end = nextList;
          }
        } else {
          // delimiter is at listPos, so set end to delimiter
          end = delimiter;
        }

        if (start != end) {
          String key = trimmedHeader(str, start, delimiter, lowercaseKeys);
          if (!key.isEmpty()) {
            String value;
            if (delimiter == mapPos) {
              value = trimmedHeader(str, delimiter + 1, end, false);
              // tags must start with a letter
              if (!value.isEmpty() && !Character.isLetter(value.charAt(0))) {
                throw new BadFormatException(
                    "Illegal tag starting with non letter for key '" + key + "'");
              }
            } else {
              // If wildcard exists, we do not allow other header mappings
              if (key.charAt(0) == '*') {
                map.clear();
                map.put(key, defaultPrefix);
                return;
              }
              if (Character.isLetter(key.charAt(0))) {
                value = defaultPrefix + normalizedHeaderTag(key);
              } else {
                // tags must start with a letter
                throw new BadFormatException(
                    "Illegal key only tag starting with non letter '" + key + "'");
              }
            }
            if (!value.isEmpty()) {
              map.put(key, value);
            }
          }
        }
        start = end + 1;
      }
    } catch (Throwable t) {
      if (t instanceof BadFormatException) {
        log.warn(
            "Invalid config for {}. {}. Must match '(key:value|key)([ ,](key:value|key))*'.",
            settingName,
            t.getMessage());
      } else {
        log.warn("Unexpected exception during config parsing of {}.", settingName, t);
      }
      map.clear();
    }
  }

  @Nonnull
  private static String trimmedHeader(String str, int start, int end, boolean lowercase) {
    if (start >= end) {
      return "";
    }
    StringBuilder builder = new StringBuilder(end - start);
    int firstNonWhiteSpace = -1;
    int lastNonWhitespace = -1;
    for (int i = start; i < end; i++) {
      char c = lowercase ? Character.toLowerCase(str.charAt(i)) : str.charAt(i);
      if (Character.isWhitespace(c)) {
        builder.append(' ');
      } else {
        firstNonWhiteSpace = firstNonWhiteSpace == -1 ? i : firstNonWhiteSpace;
        lastNonWhitespace = i;
        builder.append(c);
      }
    }
    if (firstNonWhiteSpace == -1) {
      return "";
    } else {
      str = builder.substring(firstNonWhiteSpace - start, lastNonWhitespace - start + 1);
      return str;
    }
  }

  @Nonnull
  @SuppressForbidden
  static BitSet parseIntegerRangeSet(@Nonnull String str, final String settingName)
      throws NumberFormatException {
    str = str.replaceAll("\\s", "");
    if (!str.matches("\\d{1,3}(?:-\\d{1,3})?(?:,\\d{1,3}(?:-\\d{1,3})?)*")) {
      log.warn(
          "Invalid config for {}: '{}'. Must be formatted like '400-403,405,410-499'.",
          settingName,
          str);
      throw new NumberFormatException();
    }

    final int lastSeparator = Math.max(str.lastIndexOf(','), str.lastIndexOf('-'));
    final int maxValue = Integer.parseInt(str.substring(lastSeparator + 1));
    final BitSet set = new BitSet(maxValue);
    final String[] tokens = str.split(",", -1);
    for (final String token : tokens) {
      final int separator = token.indexOf('-');
      if (separator == -1) {
        set.set(Integer.parseInt(token));
      } else if (separator > 0) {
        final int left = Integer.parseInt(token.substring(0, separator));
        final int right = Integer.parseInt(token.substring(separator + 1));
        final int min = Math.min(left, right);
        final int max = Math.max(left, right);
        set.set(min, max + 1);
      }
    }
    return set;
  }

  public static Boolean booleanValueOf(String value) {
    if ("1".equals(value)) {
      return Boolean.TRUE;
    } else if ("0".equals(value)) {
      return Boolean.FALSE;
    } else if ("true".equalsIgnoreCase(value)) {
      return Boolean.TRUE;
    } else if ("false".equalsIgnoreCase(value)) {
      return Boolean.FALSE;
    } else {
      // Throw custom exception for invalid boolean values to maintain backward compatibility
      throw new InvalidBooleanValueException("Invalid boolean value: " + value);
    }
  }

  private static class ValueOfLookup extends ClassValue<MethodHandle> {
    private static final MethodHandles.Lookup PUBLIC_LOOKUP = MethodHandles.publicLookup();

    @Override
    protected MethodHandle computeValue(Class<?> type) {
      try {
        if (Boolean.class.equals(type)) {
          return MethodHandles.lookup()
              .findStatic(
                  ConfigConverter.class,
                  "booleanValueOf",
                  MethodType.methodType(Boolean.class, String.class));
        }

        return PUBLIC_LOOKUP.findStatic(type, "valueOf", MethodType.methodType(type, String.class));
      } catch (final NoSuchMethodException | IllegalAccessException e) {
        log.debug("Can't invoke or access 'valueOf': ", e);
        throw new RuntimeException(e);
      }
    }
  }
}
