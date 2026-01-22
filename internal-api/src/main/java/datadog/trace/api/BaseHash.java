package datadog.trace.api;

import datadog.trace.util.FNV64Hash;

public final class BaseHash {
  private static volatile long baseHash;
  private static volatile String baseHashStr;

  private BaseHash() {}

  public static void recalcBaseHash(String containerTagsHash) {
    long hash = calc(containerTagsHash);
    updateBaseHash(hash);
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

  public static long calc(String containerTagsHash) {
    return calc(
        Config.get().getServiceName(),
        Config.get().getEnv(),
        Config.get().getPrimaryTag(),
        ProcessTags.getTagsForSerialization(),
        containerTagsHash);
  }

  private static long calc(
      CharSequence serviceName,
      CharSequence env,
      String primaryTag,
      CharSequence processTags,
      String containerTagsHash) {
    long hash = FNV64Hash.generateHash(serviceName.toString(), FNV64Hash.Version.v1);
    hash = FNV64Hash.continueHash(hash, env.toString(), FNV64Hash.Version.v1);
    if (primaryTag != null) hash = FNV64Hash.continueHash(hash, primaryTag, FNV64Hash.Version.v1);
    if (processTags != null) {
      hash = FNV64Hash.continueHash(hash, processTags.toString(), FNV64Hash.Version.v1);
      if (containerTagsHash != null && !containerTagsHash.isEmpty()) {
        hash = FNV64Hash.continueHash(hash, containerTagsHash, FNV64Hash.Version.v1);
      }
    }
    return hash;
  }
}
