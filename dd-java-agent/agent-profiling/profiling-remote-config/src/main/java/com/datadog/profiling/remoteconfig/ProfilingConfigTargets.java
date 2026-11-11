package com.datadog.profiling.remoteconfig;

import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.util.TagsHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ranks an {@code APM_PROFILING} payload for this application by its {@code targets} list, e.g.
 * {@code {"targets":[{"env":"staging","service":"my-app"},{"service":"x"}]}}, following
 * dd-trace-js.
 *
 * <p>Remote config only delivers the configs that apply to this application, so every payload is
 * kept and its targets only rank it: the rank is the specificity of its most specific target that
 * is consistent with this application, i.e. whose {@code service} and {@code env} are this
 * application's ones, compared normalized like the backend does, or 0 when there is none, or no
 * target. A missing {@code service} or {@code env}, or the value {@code "*"}, matches anything.
 *
 * <p>A payload is dropped, with {@link #DROPPED}, when its targets can't be trusted: it is
 * unreadable, its {@code targets} is not a list, or it has targets but none is valid. A target that
 * is not valid, e.g. with a {@code service} that is not a string, is skipped, and never matches
 * everything.
 *
 * <p>The tags of a target only have to hold for {@code service:} and {@code env:} tags, which name
 * this application's service and env like the fields do. The other tag conditions are not checked:
 * the agent has matched them, and the tracer doesn't know every tag it is matched by, e.g. the
 * Kubernetes ones. They only rank the payloads.
 */
public final class ProfilingConfigTargets {
  private static final Logger log = LoggerFactory.getLogger(ProfilingConfigTargets.class);

  private static final String WILDCARD = "*";

  // Specificity levels of a target, from the least to the most specific, following the profiling
  // Remote Config payload merging ADR, as dd-trace-js does: other tags < env < service < workload <
  // container name < pod < container ID. The infrastructure levels are recognized by the tag names
  // the Datadog agent assigns.
  private static final int LEVEL_OTHER_TAGS = 1;
  private static final int LEVEL_ENV = 2;
  private static final int LEVEL_SERVICE = 3;
  private static final Map<String, Integer> LEVEL_BY_TAG_NAME = levelsByTagName();
  // A target's level outranks any number of conditions, which only break ties within a level.
  private static final int CONDITIONS_PER_LEVEL = 1000;

  /** A valid target of a payload. */
  private static final class Target {
    // null when the target is for any service or env
    private final String service;
    private final String env;
    private final List<String> tags;

    private Target(String service, String env, List<String> tags) {
      this.service = service;
      this.env = env;
      this.tags = tags;
    }

    /**
     * Whether the target is for this application, which only its service and env tell, whether they
     * are named by the {@code service} and {@code env} fields or by {@code service:} and {@code
     * env:} tags.
     */
    boolean isFor(String appEnv, String appService) {
      return (service == null || sameNormalized(service, appService))
          && (env == null || sameNormalized(env, appEnv))
          && tagsAreFor(appEnv, appService);
    }

    // The tracer only knows its service and env: when the tags name them, they have to be this
    // application's. The other tags are assumed to hold, as the agent has matched them.
    private boolean tagsAreFor(String appEnv, String appService) {
      for (String tag : tags) {
        int separator = tag.indexOf(':');
        if (separator < 0) {
          continue;
        }
        String key = tag.substring(0, separator).toLowerCase(Locale.ROOT);
        String value = tag.substring(separator + 1);
        if (WILDCARD.equals(value)) {
          continue;
        }
        if (("service".equals(key) && !sameNormalized(value, appService))
            || ("env".equals(key) && !sameNormalized(value, appEnv))) {
          return false;
        }
      }
      return true;
    }

    int specificity() {
      int level = service != null ? LEVEL_SERVICE : env != null ? LEVEL_ENV : 0;
      for (String tag : tags) {
        level = Math.max(level, tagLevel(tag));
      }
      int conditions = (service != null ? 1 : 0) + (env != null ? 1 : 0) + tags.size();
      // clamped, so that no number of conditions reaches the next level
      return level * CONDITIONS_PER_LEVEL + Math.min(conditions, CONDITIONS_PER_LEVEL - 1);
    }
  }

  private ProfilingConfigTargets() {}

  private static Map<String, Integer> levelsByTagName() {
    Map<String, Integer> levels = new HashMap<>();
    // workload
    for (String name :
        new String[] {
          "kube_deployment",
          "kube_replica_set",
          "kube_stateful_set",
          "kube_daemon_set",
          "kube_job",
          "kube_cronjob",
          "kube_argo_rollout",
          "oshift_deployment",
          "task_family"
        }) {
      levels.put(name, 4);
    }
    // container name
    for (String name :
        new String[] {"kube_container_name", "container_name", "ecs_container_name"}) {
      levels.put(name, 5);
    }
    // pod
    for (String name : new String[] {"pod_name", "task_arn"}) {
      levels.put(name, 6);
    }
    // container ID
    levels.put("container_id", 7);
    return levels;
  }

  /** The rank of a payload that is dropped, as its targets can't be trusted. */
  public static final int DROPPED = -1;

  /**
   * The rank of a payload for this application: the higher, the more specific, and the higher the
   * priority of its values.
   *
   * <p>It is the specificity of the most specific target for this application, or 0 if there is
   * none, or no target. A target ranks by its most specific condition, and then by its number of
   * conditions.
   *
   * @return {@link #DROPPED} if the payload is dropped
   */
  @VisibleForTesting
  static int rank(String json, String env, String service) {
    return rank(json, env, service, new ArrayList<>());
  }

  static int rank(String json, String env, String service, List<String> problems) {
    Map<String, Object> payload = ApmProfilingPayload.read(json, problems);
    return payload == null ? DROPPED : rank(payload, env, service, problems);
  }

  /**
   * Same as {@link #rank(String, String, String)}, for a payload that was read already, also
   * describing the invalid targets.
   *
   * @param payload the payload, see {@link ApmProfilingPayload#read}
   * @param problems receives a description of each target that is invalid
   */
  static int rank(Map<String, Object> payload, String env, String service, List<String> problems) {
    List<Target> targets = readTargets(payload, problems);
    if (targets == null) {
      return DROPPED;
    }
    int rank = 0;
    for (Target target : targets) {
      if (target.isFor(env, service)) {
        rank = Math.max(rank, target.specificity());
      }
    }
    return rank;
  }

  /**
   * @return the valid targets of the payload, none if it has no targets; null if the payload has to
   *     be dropped
   */
  private static List<Target> readTargets(Map<String, Object> payload, List<String> problems) {
    List<Target> targets = new ArrayList<>();
    if (!payload.containsKey("targets")) {
      return targets;
    }
    Object list = payload.get("targets");
    if (!(list instanceof List)) {
      problems.add("targets is not an array");
      return null;
    }
    List<?> entries = (List<?>) list;
    for (int i = 0; i < entries.size(); i++) {
      Target target = readTarget(entries.get(i));
      if (target != null) {
        targets.add(target);
      } else {
        problems.add("targets[" + i + "] is invalid");
      }
    }
    // an all-invalid list is rejected rather than taken for an untargeted config
    if (targets.isEmpty() && !entries.isEmpty()) {
      problems.add("no valid targets");
      return null;
    }
    return targets;
  }

  /**
   * @return the target, or null if it is not valid
   */
  private static Target readTarget(Object entry) {
    if (!(entry instanceof Map)) {
      return null;
    }
    Map<?, ?> map = (Map<?, ?>) entry;
    if (!isOptionalString(map, "service") || !isOptionalString(map, "env")) {
      return null;
    }
    List<String> tags = new ArrayList<>();
    if (map.containsKey("tags")) {
      if (!(map.get("tags") instanceof List)) {
        return null;
      }
      for (Object tag : (List<?>) map.get("tags")) {
        if (!(tag instanceof String)) {
          return null;
        }
        tags.add((String) tag);
      }
    }
    return new Target(condition(map, "service"), condition(map, "env"), tags);
  }

  private static boolean isOptionalString(Map<?, ?> target, String field) {
    return !target.containsKey(field) || target.get(field) instanceof String;
  }

  /**
   * @return the value of the field, or null if it is missing or {@code "*"}, as any value is not a
   *     condition
   */
  private static String condition(Map<?, ?> target, String field) {
    Object value = target.get(field);
    return value == null || WILDCARD.equals(value) ? null : (String) value;
  }

  private static int tagLevel(String tag) {
    int separator = tag.indexOf(':');
    String name = (separator == -1 ? tag : tag.substring(0, separator)).toLowerCase(Locale.ROOT);
    Integer level = LEVEL_BY_TAG_NAME.get(name);
    return level != null ? level : LEVEL_OTHER_TAGS;
  }

  // the agent and the backend match normalized values, so compare normalized values too
  private static boolean sameNormalized(String a, String b) {
    return Objects.equals(TagsHelper.sanitize(a), TagsHelper.sanitize(b));
  }
}
