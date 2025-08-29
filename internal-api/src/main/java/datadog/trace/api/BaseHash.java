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
    StringBuilder builder = new StringBuilder(64);
    builder.append(serviceName);
    builder.append(env);

    if (primaryTag != null) {
      builder.append(primaryTag);
    }
    if (processTags != null) {
      builder.append(processTags);
      if (containerTagsHash != null && !containerTagsHash.isEmpty()) {
        builder.append(containerTagsHash);
      }
    }
    return FNV64Hash.generateHash(builder.toString(), FNV64Hash.Version.v1);
  }
}
