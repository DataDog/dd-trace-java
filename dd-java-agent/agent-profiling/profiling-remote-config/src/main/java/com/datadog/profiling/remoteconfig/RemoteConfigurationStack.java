package com.datadog.profiling.remoteconfig;

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
 * <p>Configurations with the same priority are ordered by config id, so that the outcome doesn't
 * depend on the order they were delivered in.
 *
 * <p>Not thread safe.
 */
public final class RemoteConfigurationStack {
  private static final Logger log = LoggerFactory.getLogger(RemoteConfigurationStack.class);

  /** A configuration of the stack, with the id of the remote config it comes from. */
  public static final class Entry {
    private final String configId;
    private final int priority;
    private final RemoteConfiguration configuration;

    private Entry(String configId, int priority, RemoteConfiguration configuration) {
      this.configId = configId;
      this.priority = priority;
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

  private static final Comparator<Entry> LOWEST_PRIORITY_FIRST =
      Comparator.comparingInt(Entry::priority).thenComparing(Entry::configId);

  private final Map<String, Entry> entries;

  public RemoteConfigurationStack() {
    this(new HashMap<>());
  }

  private RemoteConfigurationStack(Map<String, Entry> entries) {
    this.entries = entries;
  }

  /**
   * Stores a configuration, replacing the one with the same config id, if any.
   *
   * @param configId the id of the remote config
   * @param priority the higher, the more it overrides the other configurations
   * @param configuration what it sets
   */
  public void push(String configId, int priority, RemoteConfiguration configuration) {
    entries.put(configId, new Entry(configId, priority, configuration));
  }

  /** Removes the configuration with the config id, if any. */
  public void remove(String configId) {
    entries.remove(configId);
  }

  public boolean contains(String configId) {
    return entries.containsKey(configId);
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /**
   * @return the config ids, in order
   */
  public Set<String> configIds() {
    return new TreeSet<>(entries.keySet());
  }

  /**
   * @return the entries, from the lowest to the highest priority
   */
  public List<Entry> lowestToHighestPriority() {
    List<Entry> sorted = new ArrayList<>(entries.values());
    sorted.sort(LOWEST_PRIORITY_FIRST);
    return sorted;
  }

  /**
   * Merges the configurations into one.
   *
   * @return for each feature, the value of the highest priority configuration that sets it; a
   *     feature that none sets is not set. It is {@link RemoteConfiguration#EMPTY} for an empty
   *     stack.
   */
  public RemoteConfiguration merged() {
    // from the lowest to the highest priority, so that the higher ones overwrite the lower ones
    RemoteConfiguration merged = RemoteConfiguration.EMPTY;
    for (Entry entry : lowestToHighestPriority()) {
      merged = merged.overriddenBy(entry.configuration);
    }
    log.debug("Merged APM_PROFILING configs, lowest to highest priority: {} -> {}", this, merged);
    return merged;
  }

  /**
   * @return an independent copy, that later changes of this stack don't affect
   */
  public RemoteConfigurationStack snapshot() {
    return new RemoteConfigurationStack(new HashMap<>(entries));
  }

  @Override
  public String toString() {
    return lowestToHighestPriority().toString();
  }
}
