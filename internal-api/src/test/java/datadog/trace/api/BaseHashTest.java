package datadog.trace.api;

import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.PRIMARY_TAG;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.removeSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(WithConfigExtension.class)
class BaseHashTest {

  @BeforeEach
  void setup() {
    // start with fresh process tags
    ProcessTags.reset();
  }

  @AfterEach
  void cleanup() {
    // restore the default enablement
    removeSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED);
    ProcessTags.reset();
  }

  @Test
  void serviceUsedInHashCalculation() {
    long firstBaseHash = BaseHash.calc(null);

    injectSysConfig(SERVICE_NAME, "service-1");
    long secondBaseHash = BaseHash.calc(null);

    assertNotEquals(firstBaseHash, secondBaseHash);
  }

  @Test
  void envUsedInHashCalculation() {
    long firstBaseHash = BaseHash.calc(null);

    injectSysConfig(ENV, "env-1");
    long secondBaseHash = BaseHash.calc(null);

    assertNotEquals(firstBaseHash, secondBaseHash);
  }

  @Test
  void primaryTagUsedInHashCalculation() {
    long firstBaseHash = BaseHash.calc(null);

    injectSysConfig(PRIMARY_TAG, "region-2");
    long secondBaseHash = BaseHash.calc(null);

    assertNotEquals(firstBaseHash, secondBaseHash);
  }

  @Test
  void processTagsUsedInHashCalculation() {
    long firstBaseHash = BaseHash.calc(null);

    ProcessTags.addTag("000", "first");
    long secondBaseHash = BaseHash.calc(null);

    assertNotEquals(firstBaseHash, secondBaseHash);
    assertTrue(ProcessTags.getTagsForSerialization().toString().startsWith("000:first,"));
  }

  @Test
  void addTagTriggersBaseHashRecalculation() {
    BaseHash.recalcBaseHash("container-hash-1");
    long hashBefore = BaseHash.getBaseHash();

    ProcessTags.addTag("cluster.name", "new-cluster");

    assertNotEquals(hashBefore, BaseHash.getBaseHash());
  }

  @Test
  void recalcBaseHashPreservesLastContainerTagsHashAcrossProcessTagsChanges() {
    String containerHash = "my-container-hash";
    BaseHash.recalcBaseHash(containerHash);
    long hashWithContainerTag = BaseHash.getBaseHash();

    // a process tag is added
    ProcessTags.addTag("cluster.name", "new-cluster");

    // hash differs from before the tag was added
    assertNotEquals(hashWithContainerTag, BaseHash.getBaseHash());
    // hash equals a fresh calc with the same container hash
    assertEquals(BaseHash.calc(containerHash), BaseHash.getBaseHash());
  }

  @Test
  @WithConfig(key = EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, value = "false")
  void addTagDoesNotRecalculateBaseHashWhenProcessTagsDisabled() {
    BaseHash.recalcBaseHash(null);
    long hashBefore = BaseHash.getBaseHash();

    ProcessTags.addTag("cluster.name", "ignored");

    assertEquals(hashBefore, BaseHash.getBaseHash());
  }

  @ParameterizedTest(name = "propagateTagsEnabled={0}")
  @ValueSource(booleans = {true, false})
  void containerTagsHashUsedInHashCalculationWhenProvided(boolean propagateTagsEnabled) {
    injectSysConfig(
        EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, String.valueOf(propagateTagsEnabled));
    ProcessTags.reset();
    ProcessTags.addTag("000", "first");
    long firstBaseHash = BaseHash.calc(null);

    long secondBaseHash = BaseHash.calc("<test-container-tags-hash>");

    if (propagateTagsEnabled) {
      assertNotEquals(firstBaseHash, secondBaseHash);
    } else {
      assertEquals(firstBaseHash, secondBaseHash);
    }
  }
}
