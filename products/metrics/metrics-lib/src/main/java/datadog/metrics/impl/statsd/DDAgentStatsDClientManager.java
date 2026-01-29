package datadog.metrics.impl.statsd;

import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.LOGGING_WRITER_TYPE;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.api.statsd.StatsDClientManager;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class DDAgentStatsDClientManager implements StatsDClientManager {
  private static final DDAgentStatsDClientManager INSTANCE = new DDAgentStatsDClientManager();

  private DDAgentStatsDClientManager() {}

  private static final boolean USE_LOGGING_CLIENT =
      LOGGING_WRITER_TYPE.equals(Config.get().getWriterType());

  public static StatsDClientManager statsDClientManager() {
    return INSTANCE;
  }

  private static final AtomicInteger defaultStatsDPort =
      new AtomicInteger(Config.get().getDogsStatsDPort());

  public static void setDefaultStatsDPort(final int newPort) {
    if (newPort > 0 && defaultStatsDPort.getAndSet(newPort) != newPort) {
      INSTANCE.handleDefaultPortChange(newPort);
    }
  }

  public static int getDefaultStatsDPort() {
    return defaultStatsDPort.get();
  }

  private final ConcurrentHashMap<String, DDAgentStatsDConnection> connectionPool =
      new ConcurrentHashMap<>(4);

  @Override
  public StatsDClient statsDClient(
      final String host,
      final Integer port,
      final String namedPipe,
      final String namespace,
      final String[] constantTags,
      final boolean useAggregation) {
    Function<String, String> nameMapping = Function.identity();
    Function<String[], String[]> tagMapping = Function.identity();

    if (null != namespace) {
      nameMapping = new NameResolver(namespace);
    }

    final List<String> processTags = ProcessTags.getTagsAsStringList();
    final int processTagSize = processTags != null ? processTags.size() : 0;
    String[] finalConstantTags = constantTags;
    if (processTagSize > 0) {
      final int constantTagSize = constantTags != null ? constantTags.length : 0;
      if (constantTagSize == 0) {
        finalConstantTags = processTags.toArray(new String[0]);
      } else {
        final int tagSizeSum = processTagSize + constantTagSize;
        finalConstantTags = new String[tagSizeSum];
        int pos = 0;
        // copy constant tags
        for (; pos < constantTagSize; pos++) {
          finalConstantTags[pos] = constantTags[pos];
        }
        // copy process tags
        for (int i = 0; i < processTagSize; i++) {
          finalConstantTags[pos + i] = processTags.get(i);
        }
      }
    }

    if (null != finalConstantTags && finalConstantTags.length > 0) {
      tagMapping = new TagCombiner(finalConstantTags);
    }

    if (USE_LOGGING_CLIENT) {
      return new LoggingStatsDClient(nameMapping, tagMapping);
    } else {
      return new DDAgentStatsDClient(
          getConnection(host, port, namedPipe, useAggregation), nameMapping, tagMapping);
    }
  }

  private DDAgentStatsDConnection getConnection(
      final String host, final Integer port, final String namedPipe, boolean useAggregation) {
    String connectionKey = getConnectionKey(host, port, namedPipe, useAggregation);
    DDAgentStatsDConnection connection = connectionPool.get(connectionKey);
    if (null == connection) {
      DDAgentStatsDConnection newConnection =
          new DDAgentStatsDConnection(host, port, namedPipe, useAggregation);
      connection = connectionPool.putIfAbsent(connectionKey, newConnection);
      if (null == connection) {
        connection = newConnection;
      }
    }
    return connection;
  }

  private static String getConnectionKey(
      final String host, final Integer port, final String namedPipe, boolean useAggregation) {
    if (namedPipe != null) {
      return namedPipe + (useAggregation ? "" : ":no_aggregation");
    }
    return (null != host ? host : "?")
        + ":"
        + (null != port ? port : "?")
        + (useAggregation ? "" : ":no_aggregation");
  }

  private void handleDefaultPortChange(final int newPort) {
    for (DDAgentStatsDConnection connection : connectionPool.values()) {
      connection.handleDefaultPortChange(newPort);
    }
  }

  /** Resolves metrics names by prepending a namespace prefix. */
  static final class NameResolver implements Function<String, String> {
    private final DDCache<String, String> resolvedNames = DDCaches.newFixedSizeCache(32);
    private final Function<String, String> namePrefixer;

    NameResolver(final String namespace) {
      this.namePrefixer =
          new Function<String, String>() {
            private final String prefix = namespace + '.';

            @Override
            public String apply(final String metricName) {
              return prefix + metricName;
            }
          };
    }

    @Override
    public String apply(final String metricName) {
      return resolvedNames.computeIfAbsent(metricName, namePrefixer);
    }
  }

  /** Combines per-call metrics tags with pre-packed constant tags. */
  static final class TagCombiner implements Function<String[], String[]> {
    private final DDCache<String[], String[]> combinedTags = DDCaches.newFixedSizeArrayKeyCache(64);
    // single-element array containing the pre-packed constant tags
    private final String[] packedTags;
    private final Function<String[], String[]> tagsInserter;

    public TagCombiner(final String[] constantTags) {
      this.packedTags = pack(constantTags);
      this.tagsInserter =
          tags -> {
            // extend per-call array by one to add the pre-packed constant tags
            String[] result = new String[tags.length + 1];
            System.arraycopy(tags, 0, result, 1, tags.length);
            result[0] = packedTags[0];
            return result;
          };
    }

    @Override
    public String[] apply(final String[] tags) {
      if (null == tags || tags.length == 0) {
        return packedTags; // no per-call tags so we can use the pre-packed array
      } else {
        return combinedTags.computeIfAbsent(tags, tagsInserter);
      }
    }

    /**
     * Packs the constant tags into a single element array using the same separator as DogStatsD.
     */
    private static String[] pack(final String[] tags) {
      StringBuilder buf = new StringBuilder(tags[0]);
      for (int i = 1; i < tags.length; i++) {
        buf.append(',').append(tags[i]);
      }
      return new String[] {buf.toString()};
    }
  }
}
