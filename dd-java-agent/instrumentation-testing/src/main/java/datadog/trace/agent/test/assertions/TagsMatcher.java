package datadog.trace.agent.test.assertions;

import static datadog.trace.agent.test.assertions.Matchers.any;
import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.Matchers.isNonNull;
import static datadog.trace.api.DDTags.ERROR_MSG;
import static datadog.trace.api.DDTags.ERROR_STACK;
import static datadog.trace.api.DDTags.ERROR_TYPE;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.REQUIRED_CODE_ORIGIN_TAGS;
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;
import static datadog.trace.api.DDTags.THREAD_ID;
import static datadog.trace.api.DDTags.THREAD_NAME;
import static datadog.trace.common.sampling.RateByServiceTraceSampler.SAMPLING_AGENT_RATE;
import static datadog.trace.common.writer.ddagent.TraceMapper.SAMPLING_PRIORITY_KEY;

import datadog.trace.api.DDTags;
import java.util.HashMap;
import java.util.Map;

public final class TagsMatcher {
  final Map<String, Matcher<?>> tagMatchers;

  private TagsMatcher(Map<String, Matcher<?>> tagMatchers) {
    this.tagMatchers = tagMatchers;
  }

  public static TagsMatcher defaultTags() {
    Map<String, Matcher<?>> tagMatchers = new HashMap<>();
    tagMatchers.put(THREAD_NAME, isNonNull());
    tagMatchers.put(THREAD_ID, isNonNull());
    tagMatchers.put(RUNTIME_ID_TAG, any());
    tagMatchers.put(LANGUAGE_TAG_KEY, any());
    tagMatchers.put(SAMPLING_AGENT_RATE, any());
    tagMatchers.put(SAMPLING_PRIORITY_KEY.toString(), any());
    tagMatchers.put("_sample_rate", any());
    tagMatchers.put(DDTags.PID_TAG, any());
    tagMatchers.put(DDTags.SCHEMA_VERSION_TAG_KEY, any());
    tagMatchers.put(DDTags.PROFILING_ENABLED, any());
    tagMatchers.put(DDTags.PROFILING_CONTEXT_ENGINE, any());
    tagMatchers.put(DDTags.BASE_SERVICE, any());
    tagMatchers.put(DDTags.DSM_ENABLED, any());
    tagMatchers.put(DDTags.DJM_ENABLED, any());
    tagMatchers.put(DDTags.PARENT_ID, any());
    tagMatchers.put(DDTags.SPAN_LINKS, any()); // this is checked by LinksAsserter

    for (String tagName : REQUIRED_CODE_ORIGIN_TAGS) {
      tagMatchers.put(tagName, any());
    }
    // TODO Keep porting default tag logic
    // TODO Dev notes:
    // - it seems there is way too many logic there
    // - need to check if its related to tracing only

    return new TagsMatcher(tagMatchers);
  }

  /**
   * Requires the following tag to match the given matcher.
   *
   * @param tagName The tag name to match.
   * @param matcher The matcher to apply to the tag value.
   * @return A tag matcher that requires the following tag to match the given matcher.
   */
  public static TagsMatcher tag(String tagName, Matcher<?> matcher) {
    Map<String, Matcher<?>> tagMatchers = new HashMap<>();
    tagMatchers.put(tagName, matcher);
    return new TagsMatcher(tagMatchers);
  }

  /**
   * Requires the following tags to be present.
   *
   * @param tagNames The tag names to match.
   * @return A tag matcher that requires the following tags to be present.
   */
  public static TagsMatcher includes(String... tagNames) {
    Map<String, Matcher<?>> tagMatchers = new HashMap<>();
    for (String tagName : tagNames) {
      tagMatchers.put(tagName, any());
    }
    return new TagsMatcher(tagMatchers);
  }

  /**
   * Requires the error tags to match the given error.
   *
   * @param error The error to match.
   * @return A tag matcher that requires the error tags to match the given error.
   */
  public static TagsMatcher error(Throwable error) {
    return error(error.getClass(), error.getMessage());
  }

  /**
   * Requires the error tags to match the given error type.
   *
   * @param errorType The error type to match.
   * @return A tag matcher that requires the error tags to match the given error type.
   */
  public static TagsMatcher error(Class<? extends Throwable> errorType) {
    return error(errorType, null);
  }

  /**
   * Requires the error tags to match the given error type and message.
   *
   * @param errorType The error type to match.
   * @param message The error message to match.
   * @return A tag matcher that requires the error tags to match the given error type and message.
   */
  public static TagsMatcher error(Class<? extends Throwable> errorType, String message) {
    Map<String, Matcher<?>> tagMatchers = new HashMap<>();
    tagMatchers.put(ERROR_TYPE, Matchers.<String>validates(s -> testErrorType(errorType, s)));
    tagMatchers.put(ERROR_STACK, isNonNull());
    if (message != null) {
      tagMatchers.put(ERROR_MSG, is(message));
    }
    return new TagsMatcher(tagMatchers);
  }

  static boolean testErrorType(Class<? extends Throwable> errorType, String actual) {
    if (errorType.getName().equals(actual)) {
      return true;
    }
    try {
      // also accept type names which are subclasses of the given error type
      return errorType.isAssignableFrom(
          Class.forName(actual, false, TagsMatcher.class.getClassLoader()));
    } catch (Throwable ignore) {
      return false;
    }
  }
}
