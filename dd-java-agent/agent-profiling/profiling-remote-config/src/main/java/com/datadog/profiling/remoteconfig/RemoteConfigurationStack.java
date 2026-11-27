package com.datadog.profiling.remoteconfig;

import datadog.remoteconfig.state.ConfigKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The accepted {@link RemoteConfiguration}s, ordered by priority: a feature set by several of them
 * takes the value of the one with the highest priority, see {@link #merged()}.
 *
 * <p>Among configurations with the same priority, only the most recently pushed one counts, and it
 * replaces the others entirely, even for the features it doesn't set. A configuration that is
 * pushed again, i.e. that was updated, becomes the latest. The others are kept, so that they count
 * again if the latest is removed.
 *
 * <p>The configurations are keyed by the {@link ConfigKey} of the remote config target file they
 * come from, which identifies the file by its path, not by its config id alone: a target can be
 * replaced by another one with the same config id, e.g. one for another org, and remote config
 * applies the new target before it removes the old one.
 *
 * <p>Not thread safe.
 */
public final class RemoteConfigurationStack {
  private static final Logger log = LoggerFactory.getLogger(RemoteConfigurationStack.class);

  /** A configuration of the stack, with the id of the remote config it comes from. */
  public static final class Entry {
    private final String configId;
    private final int priority;

    /** The order the entries were pushed in, the greater the later. */
    private final long sequence;

    private final RemoteConfiguration configuration;

    private Entry(String configId, int priority, long sequence, RemoteConfiguration configuration) {
      this.configId = configId;
      this.priority = priority;
      this.sequence = sequence;
      this.configuration = configuration;
    }

    public String configId() {
      return configId;
    }

    public int priority() {
      return priority;
    }

    public RemoteConfiguration configuration() {
      return configuration;
    }

    @Override
    public String toString() {
      return configId + " (priority " + priority + "): " + configuration;
    }
  }

  /** By priority, then the oldest first so that the latest overwrites it when merged. */
  private static final Comparator<Entry> LOWEST_PRIORITY_FIRST =
      Comparator.comparingInt(Entry::priority).thenComparingLong(entry -> entry.sequence);

  private final Map<ConfigKey, Entry> entries;
  private long nextSequence;

  public RemoteConfigurationStack() {
    this(new HashMap<>(), 0);
  }

  private RemoteConfigurationStack(Map<ConfigKey, Entry> entries, long nextSequence) {
    this.entries = entries;
    this.nextSequence = nextSequence;
  }

  /**
   * Stores a configuration as the latest one, replacing the one with the same key, if any.
   *
   * @param key the remote config target file it comes from
   * @param priority the higher, the more it overrides the other configurations
   * @param configuration what it sets
   */
  public void push(ConfigKey key, int priority, RemoteConfiguration configuration) {
    entries.put(key, new Entry(key.getConfigId(), priority, nextSequence++, configuration));
  }

  /** Removes the configuration of the remote config target file, if any. */
  public void remove(ConfigKey key) {
    entries.remove(key);
  }

  /** Whether there is a configuration of the remote config target file. */
  public boolean contains(ConfigKey key) {
    return entries.containsKey(key);
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /**
   * @return the config ids, in order
   */
  public Set<String> configIds() {
    Set<String> configIds = new TreeSet<>();
    for (Entry entry : entries.values()) {
      configIds.add(entry.configId);
    }
    return configIds;
  }

  /**
   * @return the entries, from the lowest to the highest priority; among the same priority, from the
   *     oldest to the latest
   */
  public List<Entry> lowestToHighestPriority() {
    List<Entry> sorted = new ArrayList<>(entries.values());
    sorted.sort(LOWEST_PRIORITY_FIRST);
    return sorted;
  }

  /**
   * Merges the configurations into one. Of the configurations with the same priority, only the
   * latest one counts.
   *
   * @return for each feature, the value of the highest priority configuration that sets it; a
   *     feature that none sets is not set. It is {@link RemoteConfiguration#EMPTY} for an empty
   *     stack.
   */
  public RemoteConfiguration merged() {
    List<Entry> sorted = lowestToHighestPriority();
    // from the lowest to the highest priority, so that the higher ones overwrite the lower ones
    RemoteConfiguration merged = RemoteConfiguration.EMPTY;
    for (int i = 0; i < sorted.size(); i++) {
      Entry entry = sorted.get(i);
      boolean latestOfItsPriority =
          i == sorted.size() - 1 || sorted.get(i + 1).priority != entry.priority;
      if (latestOfItsPriority) {
        merged = merged.overriddenBy(entry.configuration);
      }
    }
    log.debug("Merged APM_PROFILING configs, lowest to highest priority: {} -> {}", this, merged);
    return merged;
  }

  /**
   * @return an independent copy, that later changes of this stack don't affect
   */
  public RemoteConfigurationStack snapshot() {
    return new RemoteConfigurationStack(new HashMap<>(entries), nextSequence);
  }

  @Override
  public String toString() {
    return lowestToHighestPriority().toString();
  }
}
