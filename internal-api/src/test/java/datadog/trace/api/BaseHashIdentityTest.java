package datadog.trace.api;

import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class BaseHashIdentityTest {

  @BeforeEach
  void setup() {
    ProcessTags.reset();
  }

  @AfterEach
  void cleanup() {
    ProcessTags.reset();
  }

  @Test
  void identityHashTracksServiceEnvPrimaryTagLikeBaseHash() {
    BaseHash.recalcBaseHash(null);
    long firstIdentityHash = BaseHash.getIdentityHash();

    injectSysConfig(SERVICE_NAME, "service-1");
    BaseHash.recalcBaseHash(null);
    long secondIdentityHash = BaseHash.getIdentityHash();

    assertNotEquals(firstIdentityHash, secondIdentityHash);
  }

  @Test
  void identityHashIsUnaffectedByContainerTagsHashOrProcessTags() {
    BaseHash.recalcBaseHash(null);
    long baseIdentityHash = BaseHash.getIdentityHash();

    BaseHash.recalcBaseHash("some-container-tags-hash");
    long withContainerTagsHash = BaseHash.getIdentityHash();

    ProcessTags.addTag("foo", "bar");
    long withProcessTags = BaseHash.getIdentityHash();

    // DSM2-335: identity hash must not be perturbed by per-pod/per-rollout inputs
    assertEquals(baseIdentityHash, withContainerTagsHash);
    assertEquals(baseIdentityHash, withProcessTags);
  }
}
