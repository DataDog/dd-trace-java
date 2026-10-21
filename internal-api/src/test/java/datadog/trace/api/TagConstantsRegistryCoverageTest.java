package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every tag-name constant in {@link Tags}, {@link InstrumentationTags} and {@link DDTags} is in the
 * tag registry, so instrumentation can set it by id -- or is listed here. Entries are {@code
 * Class.FIELD} names or patterns, where {@code *} matches any run of characters.
 *
 * <ul>
 *   <li>{@link #NEVER_REGISTERED}: constants deliberately never registered, with the reason. A
 *       pattern excludes every constant it matches.
 *   <li>{@link #NOT_YET_REGISTERED_FAMILIES}: whole families not registered yet, with the reason. A
 *       pattern excuses only the unregistered constants it matches, and must match at least one.
 *   <li>{@link #NOT_YET_REGISTERED}: single constants not registered yet. A ratchet: registering
 *       one fails the test until its entry is removed, so the list only gets shorter.
 * </ul>
 *
 * <p>A new constant must be registered or listed.
 */
class TagConstantsRegistryCoverageTest {
  static final List<Class<?>> TAG_CONSTANT_CLASSES =
      Arrays.asList(Tags.class, InstrumentationTags.class, DDTags.class);

  /**
   * Constants deliberately never registered, with the reason: values of a tag, name prefixes and
   * patterns, operation names, and names only the serializers write.
   */
  static final Map<String, String> NEVER_REGISTERED = new TreeMap<>();

  static {
    NEVER_REGISTERED.put(
        "DDTags.RUNTIME_VERSION_TAG", "a profiling and telemetry tag, not a span tag");
    NEVER_REGISTERED.put(
        "DDTags.THREAD_ID", "a span field, written by the serializers; never stored as a tag");
    NEVER_REGISTERED.put(
        "DDTags.THREAD_NAME", "a span field, written by the serializers; never stored as a tag");
    NEVER_REGISTERED.put(
        "DDTags.TRACE_START_TIME", "a baggage key (legacy end-to-end timing), not a tag");
    NEVER_REGISTERED.put("Tags.DD_ENV", "a log correlation (MDC) key, not a span tag");
    NEVER_REGISTERED.put("Tags.DD_SERVICE", "a log correlation (MDC) key, not a span tag");
    NEVER_REGISTERED.put("Tags.DD_VERSION", "a log correlation (MDC) key, not a span tag");
    NEVER_REGISTERED.put("DDTags.DB_STATEMENT", "unused, but public API");
    NEVER_REGISTERED.put(
        "DDTags.DD_CODE_ORIGIN_FRAME_*",
        "an instance of a name pattern (_dd.code_origin.frames.N.*), not modeled yet");
    NEVER_REGISTERED.put("DDTags.DD_CODE_ORIGIN_PREFIX", "a name prefix");
    NEVER_REGISTERED.put("DDTags.LANGUAGE_TAG_VALUE", "a value of language");
    NEVER_REGISTERED.put(
        "DDTags.PROCESS_TAGS", "written by the serializers, never stored as a span tag");
    NEVER_REGISTERED.put(
        "DDTags.SDK_OTLP_EXPORT", "written by the serializers, never stored as a span tag");
    NEVER_REGISTERED.put("InstrumentationTags.SERVLET_DISPATCH", "an operation name, not a tag");
    NEVER_REGISTERED.put("Tags.LLMOBS_*_SPAN_KIND", "values of an LLM Observability span kind");
    NEVER_REGISTERED.put(
        "Tags.MESSAGE_BUS_DESTINATION",
        "unused in production; TagInterceptorTest splits by it as a custom tag");
    NEVER_REGISTERED.put("Tags.SPAN_KIND_*", "values of span.kind");
  }

  /** Families not registered yet, with the reason. */
  static final Map<String, String> NOT_YET_REGISTERED_FAMILIES = new TreeMap<>();

  static {
    NOT_YET_REGISTERED_FAMILIES.put("Tags.OS_*", "CI Visibility, in a follow-up PR");
    NOT_YET_REGISTERED_FAMILIES.put("Tags.RUNTIME_*", "CI Visibility, in a follow-up PR");
    NOT_YET_REGISTERED_FAMILIES.put("DDTags.HOST_VCPU_COUNT", "CI Visibility, in a follow-up PR");
    NOT_YET_REGISTERED_FAMILIES.put(
        "DDTags.LIBRARY_VERSION_TAG_KEY", "CI Visibility, in a follow-up PR");
    NOT_YET_REGISTERED_FAMILIES.put("Tags.ITR_CORRELATION_ID", "CI Visibility, in a follow-up PR");
    NOT_YET_REGISTERED_FAMILIES.put("Tags.PULL_REQUEST_NUMBER", "CI Visibility, in a follow-up PR");
    NOT_YET_REGISTERED_FAMILIES.put("DDTags.CI_*", "CI Visibility, in a follow-up PR");
    NOT_YET_REGISTERED_FAMILIES.put("DDTags.TEST_*", "CI Visibility, in a follow-up PR");
    NOT_YET_REGISTERED_FAMILIES.put("Tags.CI_*", "CI Visibility, in a follow-up PR");
    NOT_YET_REGISTERED_FAMILIES.put(
        "Tags.GIT_*", "CI Visibility and source code metadata, in a follow-up PR");
    NOT_YET_REGISTERED_FAMILIES.put("Tags.TEST_*", "CI Visibility, in a follow-up PR");
  }

  /**
   * Single constants not registered yet. Remove an entry when its tag is registered. Empty: every
   * constant outside the families above is registered or deliberately never registered.
   */
  static final Set<String> NOT_YET_REGISTERED = new HashSet<>();

  @Test
  void everyTagConstantIsRegisteredOrListed() {
    List<String> unlisted = new ArrayList<>();
    for (Map.Entry<String, String> constant : tagConstants().entrySet()) {
      String name = constant.getKey();
      if (!isRegistered(constant.getValue())
          && !NOT_YET_REGISTERED.contains(name)
          && !matchesAny(NOT_YET_REGISTERED_FAMILIES.keySet(), name)
          && !matchesAny(NEVER_REGISTERED.keySet(), name)) {
        unlisted.add(name + " = \"" + constant.getValue() + "\"");
      }
    }
    assertEquals(
        Collections.emptyList(),
        unlisted,
        "register these tags in tag-conventions.yaml (or the Java overlay), or list them here");
  }

  @Test
  void noSingleNotYetRegisteredConstantIsRegistered() {
    Map<String, String> constants = tagConstants();
    List<String> registered = new ArrayList<>();
    for (String name : NOT_YET_REGISTERED) {
      String value = constants.get(name);
      if (value != null && isRegistered(value)) {
        registered.add(name);
      }
    }
    Collections.sort(registered);
    assertEquals(
        Collections.emptyList(), registered, "now registered: remove from NOT_YET_REGISTERED");
  }

  @Test
  void noNeverRegisteredConstantIsRegistered() {
    List<String> registered = new ArrayList<>();
    for (Map.Entry<String, String> constant : tagConstants().entrySet()) {
      if (matchesAny(NEVER_REGISTERED.keySet(), constant.getKey())
          && isRegistered(constant.getValue())) {
        registered.add(constant.getKey());
      }
    }
    assertEquals(Collections.emptyList(), registered, "registered, but listed as never registered");
  }

  @Test
  void everyEntryStillMatchesAConstant() {
    Map<String, String> constants = tagConstants();
    List<String> stale = new ArrayList<>();
    for (String listed : NOT_YET_REGISTERED) {
      if (!constants.containsKey(listed)) {
        stale.add(listed);
      }
    }
    for (String pattern : NEVER_REGISTERED.keySet()) {
      if (!matchesAnyConstant(pattern, constants, false)) {
        stale.add(pattern);
      }
    }
    // A family is done once none of its constants is unregistered.
    for (String pattern : NOT_YET_REGISTERED_FAMILIES.keySet()) {
      if (!matchesAnyConstant(pattern, constants, true)) {
        stale.add(pattern);
      }
    }
    Collections.sort(stale);
    assertEquals(Collections.emptyList(), stale, "no constant matches these entries: remove them");
  }

  private static boolean matchesAnyConstant(
      String pattern, Map<String, String> constants, boolean unregisteredOnly) {
    for (Map.Entry<String, String> constant : constants.entrySet()) {
      if (matches(pattern, constant.getKey())
          && !(unregisteredOnly && isRegistered(constant.getValue()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesAny(Set<String> patterns, String name) {
    for (String pattern : patterns) {
      if (matches(pattern, name)) {
        return true;
      }
    }
    return false;
  }

  /** {@code name} matches {@code pattern}, where {@code *} matches any run of characters. */
  private static boolean matches(String pattern, String name) {
    if (pattern.indexOf('*') < 0) {
      return pattern.equals(name);
    }
    StringBuilder regex = new StringBuilder();
    for (String literal : pattern.split("\\*", -1)) {
      if (regex.length() > 0) {
        regex.append(".*");
      }
      regex.append(Pattern.quote(literal));
    }
    return name.matches(regex.toString());
  }

  private static boolean isRegistered(String tag) {
    return KnownTagCodec.keyOf(tag) != 0;
  }

  /** Every public static final String constant of the tag classes, keyed {@code Class.FIELD}. */
  private static Map<String, String> tagConstants() {
    Map<String, String> constants = new TreeMap<>();
    for (Class<?> cls : TAG_CONSTANT_CLASSES) {
      for (Field field : cls.getDeclaredFields()) {
        int mods = field.getModifiers();
        if (field.getType() == String.class
            && Modifier.isPublic(mods)
            && Modifier.isStatic(mods)
            && Modifier.isFinal(mods)) {
          try {
            constants.put(cls.getSimpleName() + "." + field.getName(), (String) field.get(null));
          } catch (IllegalAccessException e) {
            throw new AssertionError(e);
          }
        }
      }
    }
    return constants;
  }
}
