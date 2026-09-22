package datadog.trace.api;

import datadog.trace.util.FNV64Hash;

public final class BaseHash {
  private static volatile long baseHash;
  private static volatile String baseHashStr;
  private static volatile String lastContainerTagsHash;

  // 0 means "not yet computed". service/env/primaryTag are fixed for the JVM's lifetime once
  // Config is built, so this only needs to be calculated once, lazily, on first read - computing
  // it eagerly in a field initializer would risk capturing a premature Config snapshot if this
  // class gets loaded (e.g. via DataStreamsTags.EMPTY) before Config settles.
  private static volatile long identityHash;

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
    if (identityHash == 0) {
      // Deliberately unsynchronized: concurrent callers all recompute from the same Config, so a
      // race just means a few redundant, identical writes rather than a correctness issue.
      identityHash =
          calcIdentity(
              Config.get().getServiceName(), Config.get().getEnv(), Config.get().getPrimaryTag());
    }
    return identityHash;
  }

  /** Test-only: lets tests set the identity hash without going through {@link Config}. */
  public static void updateIdentityHash(long hash) {
    identityHash = hash;
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
