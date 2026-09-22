package datadog.trace.api;

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
    BaseHash.resetIdentityHashEnsuredForTesting();
  }

  @Test
  void identityHashDependsOnServiceEnvAndPrimaryTag() {
    // identityHash is calculated once (service/env/primaryTag are fixed for the JVM's
    // lifetime), so this exercises the underlying hashing function directly rather than
    // via Config + recalcBaseHash.
    long base = BaseHash.calcIdentity("service", "env", "region-1");

    assertNotEquals(base, BaseHash.calcIdentity("service-2", "env", "region-1"));
    assertNotEquals(base, BaseHash.calcIdentity("service", "env-2", "region-1"));
    assertNotEquals(base, BaseHash.calcIdentity("service", "env", "region-2"));
    assertEquals(base, BaseHash.calcIdentity("service", "env", "region-1"));
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

  @Test
  void ensureIdentityHashRecalculatesFromConfigOnlyOnce() {
    // simulate identityHash's field initializer having captured a stale/premature snapshot
    BaseHash.updateIdentityHash(0L);

    BaseHash.ensureIdentityHash();
    long ensured = BaseHash.getIdentityHash();
    assertNotEquals(0L, ensured);

    // a later caller mutating the hash directly (e.g. via BaseHash.updateIdentityHash) must not
    // be clobbered by a second ensureIdentityHash() call - it only ever recalculates once
    BaseHash.updateIdentityHash(42L);
    BaseHash.ensureIdentityHash();
    assertEquals(42L, BaseHash.getIdentityHash());
  }
}
