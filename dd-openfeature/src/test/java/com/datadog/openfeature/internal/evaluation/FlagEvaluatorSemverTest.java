package com.datadog.openfeature.internal.evaluation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import dev.openfeature.sdk.MutableContext;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** SemVer ladders, whose conditions test the same attributes many times in one evaluation. */
class FlagEvaluatorSemverTest {

  private static final int STEPS = 10;

  @TableTest({
    "scenario                    | appVersion                                             | osVersion | variant",
    "lowest step                 | 1.0.0                                                  | 2.1.0     | v0     ",
    "middle step                 | 1.4.9                                                  | 2.1.0     | v4     ",
    "prerelease precedes release | 1.5.0-beta.11.rc-7+build.20261117.sha.0123456789abcdef | 2.1.0     | v4     ",
    "release of the prerelease   | 1.5.0                                                  | 2.1.0     | v5     ",
    "omitted patch               | 1.3                                                    | 2.1.0     | v3     ",
    "highest step                | 1.9.99                                                 | 2.1.0     | v9     ",
    "above the ladder            | 1.10.0                                                 | 2.1.0     | none   ",
    "below the ladder            | 0.9.0                                                  | 2.1.0     | none   ",
    "invalid version             | not-a-version                                          | 2.1.0     | none   ",
    "other attribute fails       | 1.4.9                                                  | 1.9.0     | none   ",
    "other attribute invalid     | 1.4.9                                                  | 2.x       | none   "
  })
  void evaluatesLaddersAsWithoutTheCache(
      final String appVersion, final String osVersion, final String variant) throws IOException {
    final MutableContext context = new MutableContext("user");
    context.add("app_version", appVersion);
    context.add("os_version", osVersion);

    final String value =
        new FlagEvaluator(false, (flag, subject, allocation, v, serialId) -> false)
            .evaluate(ladder(), String.class, "ladder", "default", context)
            .getValue();

    assertEquals(variant, value);
  }

  @Test
  void marksOnlyFlagsWithSemverConditions() throws IOException {
    final ServerConfiguration config = ladder();

    assertTrue(config.flags.get("ladder").hasSemverConditions);
    assertFalse(config.flags.get("plain").hasSemverConditions);
  }

  /**
   * A flag whose step {@code i} matches {@code 1.i.0 <= app_version < 1.(i+1).0} and {@code
   * os_version >= 2.0.0}, then falls back to {@code none}; and a flag without SemVer conditions.
   */
  private static ServerConfiguration ladder() throws IOException {
    final StringBuilder allocations = new StringBuilder();
    final StringBuilder variations =
        new StringBuilder("\"none\":{\"key\":\"none\",\"value\":\"none\"}");
    for (int i = 0; i < STEPS; i++) {
      variations.append(",\"v").append(i).append("\":{\"key\":\"v").append(i);
      variations.append("\",\"value\":\"v").append(i).append("\"}");
      allocations
          .append("{\"key\":\"step-")
          .append(i)
          .append("\",\"rules\":[{\"conditions\":[")
          .append(condition("app_version", "SEMVER_GTE", "1." + i + ".0"))
          .append(',')
          .append(condition("app_version", "SEMVER_LT", "1." + (i + 1) + ".0"))
          .append(',')
          .append(condition("os_version", "SEMVER_GTE", "2.0.0"))
          .append("]}],\"splits\":[{\"variationKey\":\"v")
          .append(i)
          .append("\",\"shards\":[]}],\"doLog\":false},");
    }
    allocations.append(
        "{\"key\":\"fallback\",\"splits\":[{\"variationKey\":\"none\",\"shards\":[]}],"
            + "\"doLog\":false}");
    final String json =
        "{\"flags\":{"
            + "\"ladder\":{\"key\":\"ladder\",\"enabled\":true,\"variationType\":\"STRING\","
            + "\"variations\":{"
            + variations
            + "},\"allocations\":["
            + allocations
            + "]},"
            + "\"plain\":{\"key\":\"plain\",\"enabled\":true,\"variationType\":\"STRING\","
            + "\"variations\":{\"a\":{\"key\":\"a\",\"value\":\"a\"}},\"allocations\":["
            + "{\"key\":\"rule\",\"rules\":[{\"conditions\":["
            + condition("country", "ONE_OF", null)
            + "]}],\"splits\":[{\"variationKey\":\"a\",\"shards\":[]}],\"doLog\":false}]}}}";
    return UniversalFlagConfigParser.parse(json.getBytes(UTF_8));
  }

  private static String condition(
      final String attribute, final String operator, final String value) {
    return "{\"attribute\":\""
        + attribute
        + "\",\"operator\":\""
        + operator
        + "\",\"value\":"
        + (value == null ? "[\"US\"]" : "\"" + value + "\"")
        + "}";
  }
}
