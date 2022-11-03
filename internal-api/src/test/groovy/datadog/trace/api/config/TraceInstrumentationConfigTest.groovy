package datadog.trace.api.config

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_GRPC_CLIENT_ERROR_STATUSES
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_GRPC_SERVER_ERROR_STATUSES
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_HTTP_SERVER_TAG_QUERY_STRING
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_INTEGRATIONS_ENABLED
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_LOGS_INJECTION_ENABLED
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_RESOLVER_OUTLINE_POOL_SIZE
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_RESOLVER_TYPE_POOL_SIZE
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_SERIALVERSIONUID_FIELD_INJECTION
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_TRACE_ANNOTATIONS
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_TRACE_EXECUTORS_ALL
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_TRACE_METHODS
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_EXCHANGES
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_QUEUES
import static datadog.trace.api.config.TracerConfig.DEFAULT_TRACE_ENABLED

class TraceInstrumentationConfigTest extends DDSpecification {
  def "check default config values"() {
    when:
    def config = new Config()

    then:
    config.traceEnabled == DEFAULT_TRACE_ENABLED
    config.integrationsEnabled == DEFAULT_INTEGRATIONS_ENABLED
    !config.integrationSynapseLegacyOperationName
    config.traceAnnotations == DEFAULT_TRACE_ANNOTATIONS
    config.logsInjectionEnabled == DEFAULT_LOGS_INJECTION_ENABLED
    config.logsMDCTagsInjectionEnabled
    config.traceMethods == DEFAULT_TRACE_METHODS
    config.traceExecutorsAll == DEFAULT_TRACE_EXECUTORS_ALL
    config.traceExecutors == []
    config.traceThreadPoolExecutorsExclude == [] as Set
    config.excludedClasses == []
    config.excludedClassesFile == null
    config.excludedClassLoaders == [] as Set
    config.excludedCodeSources == []
    config.httpServerTagQueryString == DEFAULT_HTTP_SERVER_TAG_QUERY_STRING
    config.httpServerRawQueryString
    !config.httpServerRawResource
    config.httpServerRouteBasedNaming == DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING
    config.httpClientTagQueryString == DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING
    config.httpClientSplitByDomain == DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN
    config.dbClientSplitByInstance == DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE
    config.dbClientSplitByInstanceTypeSuffix == DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX
    config.awsPropagationEnabled
    config.sqsPropagationEnabled
    config.kafkaClientPropagationEnabled
    !config.kafkaClientBase64DecodingEnabled
    config.jmsPropagationEnabled
    config.rabbitPropagationEnabled
    !config.messageBrokerSplitByDestination
    !config.hystrixTagsEnabled
    !config.hystrixMeasuredEnabled
    !config.igniteCacheIncludeKeys
    config.obfuscationQueryRegexp == null
    !config.playReportHttpStatus
    !config.servletPrincipalEnabled
    config.servletAsyncTimeoutError
    config.jdbcPreparedStatementClassName == ""
    config.jdbcConnectionClassName == ""
    config.rootContextServiceName == DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME
    config.runtimeContextFieldInjection == DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION
    config.serialVersionUIDFieldInjection == DEFAULT_SERIALVERSIONUID_FIELD_INJECTION
    config.grpcIgnoredInboundMethods == [] as Set
    config.grpcIgnoredOutboundMethods == [] as Set
    !config.grpcServerTrimPackageResource
    config.grpcServerErrorStatuses == DEFAULT_GRPC_SERVER_ERROR_STATUSES
    config.grpcClientErrorStatuses == DEFAULT_GRPC_CLIENT_ERROR_STATUSES
    config.resolverOutlinePoolEnabled
    config.resolverOutlinePoolSize == DEFAULT_RESOLVER_OUTLINE_POOL_SIZE
    config.resolverTypePoolSize == DEFAULT_RESOLVER_TYPE_POOL_SIZE
    config.resolverUseLoadClassEnabled
  }

  def "check kafka propagation for topic"() {
    when:
    Properties properties = new Properties()
    properties.put(KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS, property)
    def config = Config.get(properties)

    then:
    config.isKafkaClientPropagationDisabledForTopic(topic) == expected

    where:
    // spotless:off
    property                  |  topic      | expected
    ""                        | "topic1"    | false
    "topic1,topic2"           | "topic1"    | true
    "topic1,topic2"           | "topic3"    | false
    ""                        | null        | false
    "topic1,topic2"           | null        | false
    // spotless:on
  }

  def "check JMS propagation for destination"() {
    when:
    Properties properties = new Properties()
    properties.put(JMS_PROPAGATION_DISABLED_TOPICS, disabledTopics)
    properties.put(JMS_PROPAGATION_DISABLED_QUEUES, disabledQueues)
    def config = Config.get(properties)

    then:
    config.isJmsPropagationDisabledForDestination(destination) == expected

    where:
    // spotless:off
    disabledTopics     | disabledQueues      |  destination      | expected
    ""                 | ""                  | "topic1"          | false
    ""                 | ""                  | "queue1"          | false
    ""                 | ""                  | null              | false
    "topic1,topic2"    | ""                  | "topic1"          | true
    "topic1,topic2"    | ""                  | "topic3"          | false
    "topic1,topic2"    | ""                  | "queue1"          | false
    "topic1,topic2"    | ""                  | null              | false
    ""                 | "queue1,queue2"     | "topic1"          | false
    ""                 | "queue1,queue2"     | "queue1"          | true
    ""                 | "queue1,queue2"     | "queue3"          | false
    ""                 | "queue1,queue2"     | null              | false
    "topic1,topic2"    | "queue1,queue2"     | "topic1"          | true
    "topic1,topic2"    | "queue1,queue2"     | "topic3"          | false
    "topic1,topic2"    | "queue1,queue2"     | "queue1"          | true
    "topic1,topic2"    | "queue1,queue2"     | "queue3"          | false
    "topic1,topic2"    | "queue1,queue2"     | null              | false
    // spotless:on
  }

  def "check RabbitMQ propagation for destination"() {
    when:
    Properties properties = new Properties()
    properties.put(RABBIT_PROPAGATION_DISABLED_EXCHANGES, disabledExchanges)
    properties.put(RABBIT_PROPAGATION_DISABLED_QUEUES, disabledQueues)
    def config = Config.get(properties)

    then:
    config.isRabbitPropagationDisabledForDestination(destination) == expected

    where:
    // spotless:off
    disabledExchanges     | disabledQueues      |  destination      | expected
    ""                    | ""                  | "exchange1"       | false
    ""                    | ""                  | "queue1"          | false
    ""                    | ""                  | null              | false
    "exchange1,exchange2" | ""                  | "exchange1"       | true
    "exchange1,exchange2" | ""                  | "exchange3"       | false
    "exchange1,exchange2" | ""                  | "queue1"          | false
    "exchange1,exchange2" | ""                  | null              | false
    ""                    | "queue1,queue2"     | "exchange1"       | false
    ""                    | "queue1,queue2"     | "queue1"          | true
    ""                    | "queue1,queue2"     | "queue3"          | false
    ""                    | "queue1,queue2"     | null              | false
    "exchange1,exchange2" | "queue1,queue2"     | "exchange1"       | true
    "exchange1,exchange2" | "queue1,queue2"     | "exchange3"       | false
    "exchange1,exchange2" | "queue1,queue2"     | "queue1"          | true
    "exchange1,exchange2" | "queue1,queue2"     | "queue3"          | false
    "exchange1,exchange2" | "queue1,queue2"     | null              | false
    // spotless:on
  }

  def "verify integration config"() {
    setup:
    environmentVariables.set("DD_INTEGRATION_ORDER_ENABLED", "false")
    environmentVariables.set("DD_INTEGRATION_TEST_ENV_ENABLED", "true")
    environmentVariables.set("DD_INTEGRATION_DISABLED_ENV_ENABLED", "false")

    System.setProperty("dd.integration.order.enabled", "true")
    System.setProperty("dd.integration.test-prop.enabled", "true")
    System.setProperty("dd.integration.disabled-prop.enabled", "false")

    expect:
    Config.get().isIntegrationEnabled(integrationNames, defaultEnabled) == expected

    where:
    // spotless:off
    names                          | defaultEnabled | expected
    []                             | true           | true
    []                             | false          | false
    ["invalid"]                    | true           | true
    ["invalid"]                    | false          | false
    ["test-prop"]                  | false          | true
    ["test-env"]                   | false          | true
    ["disabled-prop"]              | true           | false
    ["disabled-env"]               | true           | false
    ["other", "test-prop"]         | false          | true
    ["other", "test-env"]          | false          | true
    ["order"]                      | false          | true
    ["test-prop", "disabled-prop"] | false          | true
    ["disabled-env", "test-env"]   | false          | true
    ["test-prop", "disabled-prop"] | true           | false
    ["disabled-env", "test-env"]   | true           | false
    // spotless:on

    integrationNames = new TreeSet<String>(names)
  }

  def "verify rule config #name"() {
    setup:
    environmentVariables.set("DD_TRACE_TEST_ENABLED", "true")
    environmentVariables.set("DD_TRACE_TEST_ENV_ENABLED", "true")
    environmentVariables.set("DD_TRACE_DISABLED_ENV_ENABLED", "false")

    System.setProperty("dd.trace.test.enabled", "false")
    System.setProperty("dd.trace.test-prop.enabled", "true")
    System.setProperty("dd.trace.disabled-prop.enabled", "false")

    expect:
    Config.get().isRuleEnabled(name) == enabled

    where:
    // spotless:off
    name            | enabled
    ""              | true
    "invalid"       | true
    "test-prop"     | true
    "Test-Prop"     | true
    "test-env"      | true
    "Test-Env"      | true
    "test"          | false
    "TEST"          | false
    "disabled-prop" | false
    "Disabled-Prop" | false
    "disabled-env"  | false
    "Disabled-Env"  | false
    // spotless:on
  }
  def "verify integration jmxfetch config"() {
    setup:
    environmentVariables.set("DD_JMXFETCH_ORDER_ENABLED", "false")
    environmentVariables.set("DD_JMXFETCH_TEST_ENV_ENABLED", "true")
    environmentVariables.set("DD_JMXFETCH_DISABLED_ENV_ENABLED", "false")

    System.setProperty("dd.jmxfetch.order.enabled", "true")
    System.setProperty("dd.jmxfetch.test-prop.enabled", "true")
    System.setProperty("dd.jmxfetch.disabled-prop.enabled", "false")

    expect:
    Config.get().isJmxFetchIntegrationEnabled(integrationNames, defaultEnabled) == expected

    where:
    // spotless:off
    names                          | defaultEnabled | expected
    []                             | true           | true
    []                             | false          | false
    ["invalid"]                    | true           | true
    ["invalid"]                    | false          | false
    ["test-prop"]                  | false          | true
    ["test-env"]                   | false          | true
    ["disabled-prop"]              | true           | false
    ["disabled-env"]               | true           | false
    ["other", "test-prop"]         | false          | true
    ["other", "test-env"]          | false          | true
    ["order"]                      | false          | true
    ["test-prop", "disabled-prop"] | false          | true
    ["disabled-env", "test-env"]   | false          | true
    ["test-prop", "disabled-prop"] | true           | false
    ["disabled-env", "test-env"]   | true           | false
    // spotless:on

    integrationNames = new TreeSet<String>(names)
  }

  def "verify integration trace analytics config"() {
    setup:
    environmentVariables.set("DD_ORDER_ANALYTICS_ENABLED", "false")
    environmentVariables.set("DD_TEST_ENV_ANALYTICS_ENABLED", "true")
    environmentVariables.set("DD_DISABLED_ENV_ANALYTICS_ENABLED", "false")
    // trace prefix form should take precedence over the old non-prefix form
    environmentVariables.set("DD_ALIAS_ENV_ANALYTICS_ENABLED", "false")
    environmentVariables.set("DD_TRACE_ALIAS_ENV_ANALYTICS_ENABLED", "true")

    System.setProperty("dd.order.analytics.enabled", "true")
    System.setProperty("dd.test-prop.analytics.enabled", "true")
    System.setProperty("dd.disabled-prop.analytics.enabled", "false")
    // trace prefix form should take precedence over the old non-prefix form
    System.setProperty("dd.alias-prop.analytics.enabled", "false")
    System.setProperty("dd.trace.alias-prop.analytics.enabled", "true")

    expect:
    Config.get().isTraceAnalyticsIntegrationEnabled(integrationNames, defaultEnabled) == expected

    where:
    // spotless:off
    names                           | defaultEnabled | expected
    []                              | true           | true
    []                              | false          | false
    ["invalid"]                     | true           | true
    ["invalid"]                     | false          | false
    ["test-prop"]                   | false          | true
    ["test-env"]                    | false          | true
    ["disabled-prop"]               | true           | false
    ["disabled-env"]                | true           | false
    ["other", "test-prop"]          | false          | true
    ["other", "test-env"]           | false          | true
    ["order"]                       | false          | true
    ["test-prop", "disabled-prop"]  | false          | true
    ["disabled-env", "test-env"]    | false          | true
    ["test-prop", "disabled-prop"]  | true           | false
    ["disabled-env", "test-env"]    | true           | false
    ["alias-prop", "disabled-prop"] | false          | true
    ["disabled-env", "alias-env"]   | false          | true
    ["alias-prop", "disabled-prop"] | true           | false
    ["disabled-env", "alias-env"]   | true           | false
    // spotless:on

    integrationNames = new TreeSet<String>(names)
  }
}
