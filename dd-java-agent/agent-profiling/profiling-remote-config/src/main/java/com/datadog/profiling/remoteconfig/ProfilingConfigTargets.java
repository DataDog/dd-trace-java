package com.datadog.profiling.remoteconfig;

import static java.util.Collections.emptyList;

import com.squareup.moshi.JsonReader;
import datadog.trace.util.TagsHelper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a {@code APM_PROFILING} payload is intended for this application, based on its
 * {@code targets} list, e.g. {@code
 * {"targets":[{"env":"staging","service":"my-app"},{"service":"x"}]}}, and how specific it is for
 * it, which ranks the payloads that are.
 *
 * <p>A payload matches only when one of its valid targets is consistent with this application: its
 * {@code service} and {@code env} must be this application's ones, compared normalized like the
 * backend does. A missing {@code service} or {@code env}, or the value {@code "*"}, matches
 * anything. Any other payload is rejected: one whose targets are all for another service or env,
 * has no valid target, is unreadable, or has no {@code targets} list, as it is then not a profiling
 * config (the {@code APM_PROFILING} product is shared). A target that is not valid, e.g. with a
 * {@code service} that is not a string, never matches, rather than matching everything.
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
      return level * CONDITIONS_PER_LEVEL + conditions;
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

  /** The rank of a payload that is not intended for this application. */
  public static final int NO_MATCH = -1;

  /**
   * Whether a payload is intended for this application. It is not when none of its valid targets is
   * for this application's service and env, in which case it has to be rejected.
   *
   * @param json the payload
   * @param env this application's env
   * @param service this application's service
   */
  public static boolean matches(String json, String env, String service) {
    return rank(json, env, service) != NO_MATCH;
  }

  /**
   * The specificity of a payload for this application, which ranks the payloads that match it: the
   * higher, the more specific, and the higher the priority of its values.
   *
   * <p>It is the specificity of the most specific target for this application, or 0 if there is
   * none, i.e. when the payload does not {@link #matches match}, or when its target is the least
   * specific one. A payload's targets can name several scopes, and the one it was delivered for has
   * to be consistent with them.
   *
   * <p>A target ranks by its most specific condition, and then by its number of conditions.
   *
   * @param json the payload
   * @param env this application's env
   * @param service this application's service
   */
  public static int specificity(String json, String env, String service) {
    return Math.max(0, rank(json, env, service));
  }

  /**
   * Both whether a payload is intended for this application and its specificity, from a single read
   * of the payload.
   *
   * @return {@link #NO_MATCH} if the payload does not {@link #matches match}, or else its {@link
   *     #specificity}
   */
  public static int rank(String json, String env, String service) {
    int rank = NO_MATCH;
    for (Target target : readTargets(json)) {
      if (target.isFor(env, service)) {
        rank = Math.max(rank, target.specificity());
      }
    }
    return rank;
  }

  /**
   * @return the valid targets of the payload; none if it has no targets or can't be read
   */
  private static List<Target> readTargets(String json) {
    try {
      return readTargets(JsonReader.of(new Buffer().writeUtf8(json)));
    } catch (IOException | RuntimeException e) {
      log.debug("Ignoring the targets of an unreadable APM_PROFILING config: {}", e.toString());
      return emptyList();
    }
  }

  private static List<Target> readTargets(JsonReader reader) throws IOException {
    List<Target> targets = new ArrayList<>();
    reader.beginObject();
    while (reader.hasNext()) {
      if (!"targets".equals(reader.nextName()) || reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
        reader.skipValue();
        continue;
      }
      reader.beginArray();
      while (reader.hasNext()) {
        Target target = readTarget(reader);
        if (target != null) {
          targets.add(target);
        }
      }
      reader.endArray();
    }
    return targets;
  }

  /**
   * @return the target, or null if it is not valid
   */
  private static Target readTarget(JsonReader reader) throws IOException {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue();
      return null;
    }
    String service = null;
    String env = null;
    List<String> tags = new ArrayList<>();
    boolean valid = true;
    reader.beginObject();
    while (reader.hasNext()) {
      String name = reader.nextName();
      if ("service".equals(name) || "env".equals(name)) {
        if (reader.peek() == JsonReader.Token.STRING) {
          String value = reader.nextString();
          // any service or env is not a condition
          String condition = WILDCARD.equals(value) ? null : value;
          if ("service".equals(name)) {
            service = condition;
          } else {
            env = condition;
          }
        } else {
          valid = false;
          reader.skipValue();
        }
      } else if ("tags".equals(name)) {
        if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
          reader.beginArray();
          while (reader.hasNext()) {
            if (reader.peek() == JsonReader.Token.STRING) {
              tags.add(reader.nextString());
            } else {
              valid = false;
              reader.skipValue();
            }
          }
          reader.endArray();
        } else {
          valid = false;
          reader.skipValue();
        }
      } else {
        reader.skipValue();
      }
    }
    reader.endObject();
    return valid ? new Target(service, env, tags) : null;
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
