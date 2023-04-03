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
   * Get the naming policy for clients (http, soap, ...).
   *
   * @return a {@link NamingSchema.ForCache} instance.
   */
  ForClient client();

  /**
   * Get the naming policy for databases.
   *
   * @return a {@link NamingSchema.ForDatabase} instance.
   */
  ForDatabase database();

  /**
   * Get the naming policy for servers.
   *
   * @return a {@link NamingSchema.ForServer} instance.
   */
  ForServer server();

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

  interface ForClient {
    /**
     * Calculate the operation name for a client span.
     *
     * @param protocol the protocol used (e.g. http, ftp, ..)
     * @return the operation name
     */
    @Nonnull
    String operationForProtocol(@Nonnull String protocol);

    /**
     * Calculate the operation name for a client span.
     *
     * @param component the name of the instrumentation componen
     * @return the operation name
     */
    @Nonnull
    String operationForComponent(@Nonnull String component);
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

  interface ForServer {
    /**
     * Calculate the operation name for a server span.
     *
     * @param protocol the protocol used (e.g. http, soap, rmi ..)
     * @return the operation name
     */
    @Nonnull
    String operationForProtocol(@Nonnull String protocol);

    /**
     * Calculate the operation name for a server span.
     *
     * @param component the name of the instrumentation component
     * @return the operation name
     */
    @Nonnull
    String operationForComponent(@Nonnull String component);
  }
}
