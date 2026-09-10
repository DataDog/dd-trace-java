package datadog.trace.llmobs.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Tag helpers shared by the payloads sent to the eval metric intake. */
final class IntakeTags {

  private IntakeTags() {}

  /**
   * Flattens user tags into the {@code key:value} strings the intake expects.
   *
   * @param userTags the user supplied tags, may be {@code null}
   * @param baseTags the already flattened tags to prepend, in order
   * @return the flattened tag list
   */
  static List<String> flatten(@Nullable Map<String, Object> userTags, String... baseTags) {
    List<String> tagList =
        new ArrayList<>((userTags == null ? 0 : userTags.size()) + baseTags.length);
    for (String baseTag : baseTags) {
      tagList.add(baseTag);
    }
    if (userTags != null) {
      for (Map.Entry<String, Object> entry : userTags.entrySet()) {
        tagList.add(entry.getKey() + ":" + entry.getValue());
      }
    }
    return tagList;
  }
}
