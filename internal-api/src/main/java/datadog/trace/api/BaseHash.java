package datadog.trace.api;

import datadog.trace.util.FNV64Hash;

public final class BaseHash {
  private static volatile long baseHash;
  private static volatile String baseHashStr;
  private static volatile String lastContainerTagsHash;

  // service/env/primaryTag are fixed for the JVM's lifetime once Config is built, so this only
  // needs to be calculated once rather than every time recalcBaseHash()/recalc() runs.
  private static volatile long identityHash =
      calcIdentity(
          Config.get().getServiceName(), Config.get().getEnv(), Config.get().getPrimaryTag());

  // Guards ensureIdentityHash(): this class can be loaded as a side effect of unrelated static
  // initialization (e.g. DataStreamsTags.EMPTY) well before Config's values have settled, so
  // identityHash's field initializer above may capture a premature snapshot. ensureIdentityHash()
  // gets one chance to recalculate it from a (hopefully by-then-settled) Config.
  private static volatile boolean identityHashEnsured;

  private BaseHash() {}

  public static void recalcBaseHash(String containerTagsHash) {
    lastContainerTagsHash = containerTagsHash;
    recalc();
  }

  static void recalcBaseHash() {
    recalc();
  }

  private static void recalc() {
    updateBaseHash(calc(lastContainerTagsHash));
  }

  public static void updateBaseHash(long hash) {
    baseHash = hash;
    baseHashStr = Long.toString(hash);
  }

  public static long getBaseHash() {
    return baseHash;
  }

  public static String getBaseHashStr() {
    return baseHashStr;
  }

  /**
   * DSM topology-identity hash: service + env + primary tag only. Unlike {@link #getBaseHash()}
   * (which also folds in process tags and the agent-reported container-tags hash for DBM's
   * per-container SQL attribution use case), this is stable across pod restarts / rolling deploys,
   * so it's safe to use as the seed for DSM's cardinality-sensitive pathway hash.
   */
  public static long getIdentityHash() {
    return identityHash;
  }

  /**
   * Recalculates {@link #identityHash} from the current {@link Config}, but only the first time
   * it's called. Callers should invoke this right before the first outbound DSM checkpoint is set,
   * so that if {@link #identityHash}'s field initializer ran prematurely (before {@link Config}'s
   * values settled), it gets one chance to pick up the settled values before any pathway hash is
   * actually reported.
   */
  public static void ensureIdentityHash() {
    if (!identityHashEnsured) {
      synchronized (BaseHash.class) {
        if (!identityHashEnsured) {
          identityHash =
              calcIdentity(
                  Config.get().getServiceName(),
                  Config.get().getEnv(),
                  Config.get().getPrimaryTag());
          identityHashEnsured = true;
        }
      }
    }
  }

  /** Test-only: lets tests set the identity hash without going through {@link Config}. */
  public static void updateIdentityHash(long hash) {
    identityHash = hash;
  }

  /** Test-only: lets tests re-exercise {@link #ensureIdentityHash()}'s one-time guard. */
  static void resetIdentityHashEnsuredForTesting() {
    identityHashEnsured = false;
  }

  public static long calc(String containerTagsHash) {
    return calc(
        Config.get().getServiceName(),
        Config.get().getEnv(),
        Config.get().getPrimaryTag(),
        ProcessTags.getTagsForSerialization(),
        containerTagsHash);
  }

  static long calcIdentity(CharSequence serviceName, CharSequence env, String primaryTag) {
    long hash = FNV64Hash.generateHash(serviceName.toString(), FNV64Hash.Version.v1);
    hash = FNV64Hash.continueHash(hash, env.toString(), FNV64Hash.Version.v1);
    if (primaryTag != null) {
      hash = FNV64Hash.continueHash(hash, primaryTag, FNV64Hash.Version.v1);
    }
    return hash;
  }

  private static long calc(
      CharSequence serviceName,
      CharSequence env,
      String primaryTag,
      CharSequence processTags,
      String containerTagsHash) {
    long hash = calcIdentity(serviceName, env, primaryTag);
    if (processTags != null) {
      hash = FNV64Hash.continueHash(hash, processTags.toString(), FNV64Hash.Version.v1);
      if (containerTagsHash != null && !containerTagsHash.isEmpty()) {
        hash = FNV64Hash.continueHash(hash, containerTagsHash, FNV64Hash.Version.v1);
      }
    }
    return hash;
  }
}
