package datadog.trace.api;

import datadog.trace.util.FNV64Hash;

public final class ServiceHash {

  public static long getBaseHash(WellKnownTags wellKnownTags, String containerTagsHash) {
    return getBaseHash(
        wellKnownTags.getService(),
        wellKnownTags.getEnv(),
        Config.get().getPrimaryTag(),
        ProcessTags.getTagsForSerialization(),
        containerTagsHash);
  }

  public static long getBaseHash(
      CharSequence serviceName, CharSequence env, String containerTagsHash) {
    return getBaseHash(
        serviceName,
        env,
        Config.get().getPrimaryTag(),
        ProcessTags.getTagsForSerialization(),
        containerTagsHash);
  }

  private static long getBaseHash(
      CharSequence serviceName,
      CharSequence env,
      String primaryTag,
      CharSequence processTags,
      String containerTagsHash) {
    StringBuilder builder = new StringBuilder();
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
