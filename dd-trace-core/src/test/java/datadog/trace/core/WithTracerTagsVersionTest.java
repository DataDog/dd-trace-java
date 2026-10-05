package datadog.trace.core;

import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.Config;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

/**
 * {@code withTracerTags} keeps {@code version} OUT of the trace-level bundle so a per-span {@code
 * removeTag(VERSION)} doesn't mint a read-through tombstone -- EXCEPT when {@code version} is a
 * split-service tag, where the {@code TagInterceptor} must still see it to derive the service name
 * (regression guard for the level-split consumer).
 */
class WithTracerTagsVersionTest extends DDCoreJavaSpecification {

  private static TagMap tracerTagsWithVersion(Config config) {
    TagMap userTags = TagMap.create();
    userTags.set(Tags.VERSION, "1.2.3");
    return CoreTracer.withTracerTags(userTags, config, null);
  }

  @Test
  void versionStrippedFromBundleByDefault() {
    assertNull(
        tracerTagsWithVersion(Config.get()).getString(Tags.VERSION),
        "version is kept out of the trace-level bundle by default (avoids per-span tombstone)");
  }

  @Test
  @WithConfig(key = SPLIT_BY_TAGS, value = "version")
  void versionKeptInBundleWhenSplitByVersion() {
    assertEquals(
        "1.2.3",
        tracerTagsWithVersion(Config.get()).getString(Tags.VERSION),
        "version must stay in the bundle so split-by-tags can derive the service name");
  }
}
