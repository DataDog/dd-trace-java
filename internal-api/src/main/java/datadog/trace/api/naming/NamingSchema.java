package datadog.trace.api.naming;

import javax.annotation.Nonnull;

public interface NamingSchema {
  /**
   * Get the naming policy for caches.
   *
   * @return a {@link NamingSchema.ForCache} instance.
   */
  ForCache cache();

  /**
   * Get the naming policy for databases.
   *
   * @return a {@link NamingSchema.ForDatabase} instance.
   */
  ForDatabase database();

  interface ForCache {
    /**
     * Calculate the operation name for a cache span.
     *
     * @param cacheSystem the caching system (e.g. redis, memcached,..)
     * @return the operation name
     */
    @Nonnull
    String operation(@Nonnull String cacheSystem);

    /**
     * Calculate the service name for a cache span.
     *
     * @param ddService the configured service name as set by the user.
     * @param cacheSystem the caching system (e.g. redis, memcached,..)
     * @return the service name
     */
    @Nonnull
    String service(@Nonnull String ddService, @Nonnull String cacheSystem);
  }

  interface ForDatabase {
    /**
     * Calculate the operation name for a database span.
     *
     * @param databaseType the database type (e.g. postgres, elasticsearch,..)
     * @return the operation name
     */
    @Nonnull
    String operation(@Nonnull String databaseType);

    /**
     * Calculate the service name for a database span.
     *
     * @param ddService the configured service name as set by the user.
     * @param databaseType the database type (e.g. postgres, elasticsearch,..)
     * @return the service name
     */
    @Nonnull
    String service(@Nonnull String ddService, @Nonnull String databaseType);
  }
}
