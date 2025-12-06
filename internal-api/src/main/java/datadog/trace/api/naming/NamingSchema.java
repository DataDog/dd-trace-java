package datadog.trace.api.naming;

import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
   * @return a {@link NamingSchema.ForClient} instance.
   */
  ForClient client();

  /**
   * Get the naming policy for cloud providers (aws, gpc, azure, ...).
   *
   * @return a {@link NamingSchema.ForCloud} instance.
   */
  ForCloud cloud();

  /**
   * Get the naming policy for databases.
   *
   * @return a {@link NamingSchema.ForDatabase} instance.
   */
  ForDatabase database();

  /**
   * Get the naming policy for messaging.
   *
   * @return a {@link NamingSchema.ForMessaging} instance.
   */
  ForMessaging messaging();

  /**
   * Get the naming policy for servers.
   *
   * @return a {@link NamingSchema.ForServer} instance.
   */
  ForServer server();

  /**
   * Policy for peer service tags calculation
   *
   * @return
   */
  ForPeerService peerService();

  /**
   * If true, the schema allows having service names != DD_SERVICE
   *
   * @return
   */
  boolean allowInferredServices();

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
     * @param cacheSystem the caching system (e.g. redis, memcached,..)
     * @return the service name
     */
    String service(@Nonnull String cacheSystem);
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

  interface ForCloud {

    /**
     * Calculate the operation name for a generic cloud sdk call.
     *
     * @param provider the cloud provider
     * @param cloudService the cloud service name (e.g. s3)
     * @param serviceOperation the qualified service operation (e.g.S3.CreateBucket)
     * @return the operation name for this span
     */
    @Nonnull
    String operationForRequest(
        @Nonnull String provider, @Nonnull String cloudService, @Nonnull String serviceOperation);

    /**
     * Calculate the service name for a generic cloud sdk call.
     *
     * @param provider the cloud provider
     * @param cloudService the cloud service name (e.g. s3). If not provided the method should
     *     return a default value
     * @return the service name for this span
     */
    String serviceForRequest(@Nonnull String provider, @Nullable String cloudService);

    /**
     * Calculate the operation name for a function as a service invocation (e.g. aws lambda)
     *
     * @param provider the cloud provider
     * @return the operation name for this span
     */
    @Nonnull
    String operationForFaas(@Nonnull String provider);
  }

  interface ForDatabase {
    /**
     * Normalize the cache name from the raw parsed one.
     *
     * @param rawName the raw name
     * @return the normalized one
     */
    String normalizedName(@Nonnull String rawName);

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
     * @param databaseType the database type (e.g. postgres, elasticsearch,..)
     * @return the service name
     */
    String service(@Nonnull String databaseType);
  }

  interface ForMessaging {
    /**
     * Calculate the operation name for a messaging consumer span for process operation.
     *
     * @param messagingSystem the messaging system (e.g. jms, kafka,..)
     * @return the operation name
     */
    @Nonnull
    String inboundOperation(@Nonnull String messagingSystem);

    /**
     * Calculate the service name for a messaging producer span.
     *
     * @param messagingSystem the messaging system (e.g. jms, kafka, amqp,..)
     * @param useLegacyTracing if true legacy tracing service naming will be applied if compatible
     * @return the supplier for the service name
     */
    Supplier<String> inboundService(@Nonnull String messagingSystem, boolean useLegacyTracing);

    /**
     * Calculate the operation name for a messaging producer span.
     *
     * @param messagingSystem the messaging system (e.g. jms, kafka, amqp,..)
     * @return the operation name
     */
    @Nonnull
    String outboundOperation(@Nonnull String messagingSystem);

    /**
     * Calculate the service name for a messaging producer span.
     *
     * @param messagingSystem the messaging system (e.g. jms, kafka, amqp,..)
     * @param useLegacyTracing if true legacy tracing service naming will be applied if compatible
     * @return the service name
     */
    Supplier<String> outboundService(@Nonnull String messagingSystem, boolean useLegacyTracing);

    /**
     * Calculate the service name for a messaging time in queue synthetic span.
     *
     * @param messagingSystem the messaging system (e.g. jms, kafka, amqp,..)
     * @return the service name supplier
     */
    @Nonnull
    Supplier<String> timeInQueueService(@Nonnull String messagingSystem);

    /**
     * Calculate the operation name for a messaging time in queue synthetic span.
     *
     * @param messagingSystem the messaging system (e.g. jms, kafka, amqp,..)
     * @return the operation name
     */
    @Nonnull
    String timeInQueueOperation(@Nonnull String messagingSystem);
  }

  interface ForPeerService {
    /**
     * Whenever the schema supports peer service calculation
     *
     * @return
     */
    boolean supports();

    /**
     * Calculate the tags to be added to a span to represent the peer service
     *
     * @param unsafeTags the span tags. Map to be mutated
     */
    @Nonnull
    void tags(@Nonnull Map<String, Object> unsafeTags);
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
