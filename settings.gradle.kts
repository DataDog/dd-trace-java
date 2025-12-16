pluginManagement {
  repositories {
    mavenLocal()

    if (settings.extra.has("gradlePluginProxy")) {
      maven {
        url = uri(settings.extra["gradlePluginProxy"] as String)
        isAllowInsecureProtocol = true
      }
    }
    if (settings.extra.has("mavenRepositoryProxy")) {
      maven {
        url = uri(settings.extra["mavenRepositoryProxy"] as String)
        isAllowInsecureProtocol = true
      }
    }
    gradlePluginPortal()
    mavenCentral()
  }
}

plugins {
  id("com.gradle.develocity") version "4.3"
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

val isCI = providers.environmentVariable("CI")
val skipBuildscan = providers.environmentVariable("SKIP_BUILDSCAN").map { it.toBoolean() }.orElse(false)

develocity {
  buildScan {
    termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
    termsOfUseAgree = "yes"
    publishing.onlyIf { isCI.isPresent && !skipBuildscan.get() }
  }
}

// Don't pollute the dependency cache with the build cache
if (isCI.isPresent) {
  buildCache {
    local {
      directory = File(rootDir, "workspace/build-cache")
    }
  }
}

rootProject.name = "dd-trace-java"

// external apis
include(
  ":dd-trace-api",
  ":dd-trace-ot",
  ":dd-trace-ot:correlation-id-injection",
)

// agent projects
include(
  ":internal-api",
  ":internal-api:internal-api-9",
  ":dd-trace-core",
  ":dd-java-agent",
  ":dd-java-agent:agent-bootstrap",
  ":dd-java-agent:agent-builder",
  ":dd-java-agent:agent-tooling",
  ":dd-java-agent:agent-jmxfetch",
  ":dd-java-agent:agent-logging",
  ":dd-java-agent:agent-logs-intake",
  ":dd-java-agent:load-generator",
)

// profiling
include(
  ":dd-java-agent:agent-profiling",
  ":dd-java-agent:agent-profiling:profiling-ddprof",
  ":dd-java-agent:agent-profiling:profiling-controller",
  ":dd-java-agent:agent-profiling:profiling-controller-jfr",
  ":dd-java-agent:agent-profiling:profiling-controller-jfr:implementation",
  ":dd-java-agent:agent-profiling:profiling-controller-ddprof",
  ":dd-java-agent:agent-profiling:profiling-controller-openjdk",
  ":dd-java-agent:agent-profiling:profiling-controller-oracle",
  ":dd-java-agent:agent-profiling:profiling-testing",
  ":dd-java-agent:agent-profiling:profiling-uploader",
  ":dd-java-agent:agent-profiling:profiling-utils",
)

include(
  ":dd-java-agent:agent-debugger:debugger-bootstrap",
  ":dd-java-agent:agent-debugger:debugger-test-scala",
  ":dd-java-agent:agent-debugger:debugger-el",
)

include(
  ":dd-java-agent:agent-crashtracking",
  ":dd-java-agent:ddprof-lib",
)

include(
  ":dd-java-agent:agent-otel:otel-bootstrap",
  ":dd-java-agent:agent-otel:otel-shim",
  ":dd-java-agent:agent-otel:otel-tooling",
)

include(
  ":communication",
  ":components:context",
  ":components:environment",
  ":components:json",
  ":components:native-loader",
  ":components:yaml",
  ":telemetry",
  ":remote-config:remote-config-api",
  ":remote-config:remote-config-core",
)

include(
  ":dd-java-agent:appsec",
  ":dd-java-agent:appsec:appsec-test-fixtures",
)

// ci-visibility
include(
  ":dd-java-agent:agent-ci-visibility",
  ":dd-java-agent:agent-ci-visibility:civisibility-test-fixtures",
  ":dd-java-agent:agent-ci-visibility:civisibility-instrumentation-test-fixtures",
)

// llm-observability
include(
  ":dd-java-agent:agent-llmobs",
)

// iast
include(
  ":dd-java-agent:agent-iast",
  ":dd-java-agent:agent-iast:iast-test-fixtures",
)

include(
  ":dd-java-agent:cws-tls",
)

// AI Guard
include(":dd-java-agent:agent-aiguard")

// Feature Flagging
include(
  ":products:feature-flagging:agent",
  ":products:feature-flagging:api",
  ":products:feature-flagging:bootstrap",
  ":products:feature-flagging:lib"
)

// misc
include(
  ":dd-java-agent:testing",
  ":utils:config-utils",
  ":utils:container-utils",
  ":utils:filesystem-utils",
  ":utils:flare-utils",
  ":utils:socket-utils",
  ":utils:test-agent-utils:decoder",
  ":utils:test-utils",
  ":utils:time-utils",
  ":utils:version-utils",
)

// smoke tests
include(
  ":dd-smoke-tests:apm-tracing-disabled",
  ":dd-smoke-tests:armeria-grpc",
  ":dd-smoke-tests:backend-mock",
  ":dd-smoke-tests:cli",
  ":dd-smoke-tests:concurrent:java-8",
  ":dd-smoke-tests:concurrent:java-21",
  ":dd-smoke-tests:concurrent:java-25",
  ":dd-smoke-tests:crashtracking",
  ":dd-smoke-tests:custom-systemloader",
  ":dd-smoke-tests:dynamic-config",
  ":dd-smoke-tests:field-injection",
  ":dd-smoke-tests:gradle",
  ":dd-smoke-tests:grpc-1.5",
  ":dd-smoke-tests:java9-modules",
  ":dd-smoke-tests:jersey-2",
  ":dd-smoke-tests:jersey-3",
  ":dd-smoke-tests:jboss-modules",
  ":dd-smoke-tests:junit-console",
  ":dd-smoke-tests:kafka-2",
  ":dd-smoke-tests:kafka-3",
  ":dd-smoke-tests:lib-injection",
  ":dd-smoke-tests:log-injection",
  ":dd-smoke-tests:maven",
  ":dd-smoke-tests:openfeature",
  ":dd-smoke-tests:opentracing",
  ":dd-smoke-tests:opentelemetry",
  ":dd-smoke-tests:osgi",
  ":dd-smoke-tests:play-2.4",
  ":dd-smoke-tests:play-2.5",
  ":dd-smoke-tests:play-2.6",
  ":dd-smoke-tests:play-2.7",
  ":dd-smoke-tests:play-2.8",
  ":dd-smoke-tests:play-2.8-otel",
  ":dd-smoke-tests:play-2.8-split-routes",
  ":dd-smoke-tests:profiling-integration-tests",
  ":dd-smoke-tests:quarkus",
  ":dd-smoke-tests:quarkus-native",
  ":dd-smoke-tests:sample-trace",
  ":dd-smoke-tests:ratpack-1.5",
  ":dd-smoke-tests:resteasy",
  ":dd-smoke-tests:rum",
  ":dd-smoke-tests:rum:tomcat-9",
  ":dd-smoke-tests:rum:tomcat-10",
  ":dd-smoke-tests:rum:tomcat-11",
  ":dd-smoke-tests:rum:wildfly-15",
  ":dd-smoke-tests:spring-boot-3.0-native",
  ":dd-smoke-tests:spring-boot-2.4-webflux",
  ":dd-smoke-tests:spring-boot-2.5-webflux",
  ":dd-smoke-tests:spring-boot-2.6-webflux",
  ":dd-smoke-tests:spring-boot-2.7-webflux",
  ":dd-smoke-tests:spring-boot-3.0-webflux",
  ":dd-smoke-tests:spring-boot-2.3-webmvc-jetty",
  ":dd-smoke-tests:spring-boot-2.6-webmvc",
  ":dd-smoke-tests:spring-boot-3.0-webmvc",
  ":dd-smoke-tests:spring-boot-3.3-webmvc",
  ":dd-smoke-tests:spring-boot-rabbit",
  ":dd-smoke-tests:spring-security",
  ":dd-smoke-tests:springboot",
  ":dd-smoke-tests:springboot-freemarker",
  ":dd-smoke-tests:springboot-grpc",
  ":dd-smoke-tests:springboot-java-11",
  ":dd-smoke-tests:springboot-java-17",
  ":dd-smoke-tests:springboot-jetty-jsp",
  ":dd-smoke-tests:springboot-jpa",
  ":dd-smoke-tests:springboot-mongo",
  ":dd-smoke-tests:springboot-openliberty-20",
  ":dd-smoke-tests:springboot-openliberty-23",
  ":dd-smoke-tests:springboot-thymeleaf",
  ":dd-smoke-tests:springboot-tomcat",
  ":dd-smoke-tests:springboot-tomcat-jsp",
  ":dd-smoke-tests:springboot-velocity",
  ":dd-smoke-tests:tracer-flare",
  ":dd-smoke-tests:vertx-3.4",
  ":dd-smoke-tests:vertx-3.9",
  ":dd-smoke-tests:vertx-3.9-resteasy",
  ":dd-smoke-tests:vertx-4.2",
  ":dd-smoke-tests:wildfly",
  ":dd-smoke-tests:appsec",
  ":dd-smoke-tests:appsec:spring-tomcat7",
  ":dd-smoke-tests:appsec:springboot",
  ":dd-smoke-tests:appsec:springboot-grpc",
  ":dd-smoke-tests:appsec:springboot-graphql",
  ":dd-smoke-tests:appsec:springboot-security",
  ":dd-smoke-tests:debugger-integration-tests",
  ":dd-smoke-tests:datastreams:kafkaschemaregistry",
  ":dd-smoke-tests:iast-propagation",
  ":dd-smoke-tests:iast-util",
  ":dd-smoke-tests:iast-util:iast-util-11",
  ":dd-smoke-tests:iast-util:iast-util-17",
  // TODO this fails too often with a jgit failure, so disable until fixed
  // ":dd-smoke-tests:debugger-integration-tests:latest-jdk-app",
)

// annotation processor for checking instrumentation advice
include(
  ":dd-java-agent:instrumentation-annotation-processor",
)

// utilities and fixtures for instrumentation tests
include(
  ":dd-java-agent:instrumentation-testing",
)

// instrumentation:
include(
  ":dd-java-agent:instrumentation:aerospike-4.0",
  ":dd-java-agent:instrumentation:akka:akka-actor-2.5",
  ":dd-java-agent:instrumentation:akka:akka-http:akka-http-10.0",
  ":dd-java-agent:instrumentation:akka:akka-http:akka-http-10.2-iast",
  // dd-java-agent:instrumentation:akka:akka-http:akka-http-10.6 will be included when `akkaRepositoryToken` is present, see next `include` block.
  ":dd-java-agent:instrumentation:apache-httpclient:apache-httpasyncclient-4.0",
  ":dd-java-agent:instrumentation:apache-httpclient:apache-httpclient-4.0",
  ":dd-java-agent:instrumentation:apache-httpclient:apache-httpclient-5.0",
  ":dd-java-agent:instrumentation:apache-httpcore:apache-httpcore-4.0",
  ":dd-java-agent:instrumentation:apache-httpcore:apache-httpcore-5.0",
  ":dd-java-agent:instrumentation:armeria:armeria-grpc-0.84",
  ":dd-java-agent:instrumentation:armeria:armeria-jetty-1.24",
  ":dd-java-agent:instrumentation:avro",
  ":dd-java-agent:instrumentation:aws-java:aws-java-common",
  ":dd-java-agent:instrumentation:aws-java:aws-java-dynamodb-2.0",
  ":dd-java-agent:instrumentation:aws-java:aws-java-eventbridge-2.0",
  ":dd-java-agent:instrumentation:aws-java:aws-java-lambda-handler-1.2",
  ":dd-java-agent:instrumentation:aws-java:aws-java-s3-2.0",
  ":dd-java-agent:instrumentation:aws-java:aws-java-sdk-1.11",
  ":dd-java-agent:instrumentation:aws-java:aws-java-sdk-2.2",
  ":dd-java-agent:instrumentation:aws-java:aws-java-sfn-2.0",
  ":dd-java-agent:instrumentation:aws-java:aws-java-sns-1.0",
  ":dd-java-agent:instrumentation:aws-java:aws-java-sns-2.0",
  ":dd-java-agent:instrumentation:aws-java:aws-java-sqs-1.0",
  ":dd-java-agent:instrumentation:aws-java:aws-java-sqs-2.0",
  ":dd-java-agent:instrumentation:axis2-1.3",
  ":dd-java-agent:instrumentation:axway-api-7.5",
  ":dd-java-agent:instrumentation:azure-functions-1.2.2",
  ":dd-java-agent:instrumentation:caffeine",
  ":dd-java-agent:instrumentation:cdi-1.2",
  ":dd-java-agent:instrumentation:classloading:jboss-testing",
  ":dd-java-agent:instrumentation:classloading:jsr14-testing",
  ":dd-java-agent:instrumentation:classloading:osgi-testing",
  ":dd-java-agent:instrumentation:classloading:tomcat-testing",
  ":dd-java-agent:instrumentation:classloading",
  ":dd-java-agent:instrumentation:commons-codec-1.1",
  ":dd-java-agent:instrumentation:commons-fileupload-1.5",
  ":dd-java-agent:instrumentation:commons-httpclient-2.0",
  ":dd-java-agent:instrumentation:commons-lang:commons-lang-2.1",
  ":dd-java-agent:instrumentation:commons-lang:commons-lang-3.5",
  ":dd-java-agent:instrumentation:commons-text-1.0",
  ":dd-java-agent:instrumentation:confluent-schema-registry:confluent-schema-registry-4.1",
  ":dd-java-agent:instrumentation:couchbase:couchbase-2.0",
  ":dd-java-agent:instrumentation:couchbase:couchbase-2.6",
  ":dd-java-agent:instrumentation:couchbase:couchbase-3.1",
  ":dd-java-agent:instrumentation:couchbase:couchbase-3.2",
  ":dd-java-agent:instrumentation:cucumber",
  ":dd-java-agent:instrumentation:cxf-2.1",
  ":dd-java-agent:instrumentation:datanucleus-4",
  ":dd-java-agent:instrumentation:datastax-cassandra:datastax-cassandra-3.0",
  ":dd-java-agent:instrumentation:datastax-cassandra:datastax-cassandra-3.8",
  ":dd-java-agent:instrumentation:datastax-cassandra:datastax-cassandra-4.0",
  ":dd-java-agent:instrumentation:dropwizard:dropwizard-views",
  ":dd-java-agent:instrumentation:dropwizard",
  ":dd-java-agent:instrumentation:elasticsearch:rest-5",
  ":dd-java-agent:instrumentation:elasticsearch:rest-6.4",
  ":dd-java-agent:instrumentation:elasticsearch:rest-7",
  ":dd-java-agent:instrumentation:elasticsearch:transport-2",
  ":dd-java-agent:instrumentation:elasticsearch:transport-5.3",
  ":dd-java-agent:instrumentation:elasticsearch:transport-5",
  ":dd-java-agent:instrumentation:elasticsearch:transport-6",
  ":dd-java-agent:instrumentation:elasticsearch:transport-7.3",
  ":dd-java-agent:instrumentation:elasticsearch:transport",
  ":dd-java-agent:instrumentation:elasticsearch",
  ":dd-java-agent:instrumentation:enable-wallclock-profiling",
  ":dd-java-agent:instrumentation:exception-profiling",
  ":dd-java-agent:instrumentation:finatra-2.9",
  ":dd-java-agent:instrumentation:freemarker:freemarker-2.3.24",
  ":dd-java-agent:instrumentation:freemarker:freemarker-2.3.9",
  ":dd-java-agent:instrumentation:glassfish-3.0",
  ":dd-java-agent:instrumentation:google-http-client-1.19",
  ":dd-java-agent:instrumentation:google-pubsub",
  ":dd-java-agent:instrumentation:graal:native-image",
  ":dd-java-agent:instrumentation:gradle-testing",
  ":dd-java-agent:instrumentation:gradle:gradle-3.0",
  ":dd-java-agent:instrumentation:gradle:gradle-8.3",
  ":dd-java-agent:instrumentation:graphql-java:graphql-java-14.0",
  ":dd-java-agent:instrumentation:graphql-java:graphql-java-20.0",
  ":dd-java-agent:instrumentation:graphql-java:graphql-java-common",
  ":dd-java-agent:instrumentation:grizzly:grizzly-2.0",
  ":dd-java-agent:instrumentation:grizzly:grizzly-client-1.9",
  ":dd-java-agent:instrumentation:grizzly:grizzly-http-2.3.20",
  ":dd-java-agent:instrumentation:grpc-1.5",
  ":dd-java-agent:instrumentation:gson-1.6",
  ":dd-java-agent:instrumentation:guava-10.0",
  ":dd-java-agent:instrumentation:hazelcast:hazelcast-3.6",
  ":dd-java-agent:instrumentation:hazelcast:hazelcast-3.9",
  ":dd-java-agent:instrumentation:hazelcast:hazelcast-4.0",
  ":dd-java-agent:instrumentation:hibernate:core-3.3",
  ":dd-java-agent:instrumentation:hibernate:core-4.0",
  ":dd-java-agent:instrumentation:hibernate:core-4.3",
  ":dd-java-agent:instrumentation:hibernate",
  ":dd-java-agent:instrumentation:http-url-connection",
  ":dd-java-agent:instrumentation:hystrix-1.4",
  ":dd-java-agent:instrumentation:iast-instrumenter",
  ":dd-java-agent:instrumentation:ignite-2.0",
  ":dd-java-agent:instrumentation:jackson-core:jackson-core-1",
  ":dd-java-agent:instrumentation:jackson-core:jackson-core-2.12",
  ":dd-java-agent:instrumentation:jackson-core:jackson-core-2.16",
  ":dd-java-agent:instrumentation:jackson-core:jackson-core-2.6",
  ":dd-java-agent:instrumentation:jackson-core:jackson-core-2.8",
  ":dd-java-agent:instrumentation:jackson-core:jackson-core-2",
  ":dd-java-agent:instrumentation:jackson-core",
  ":dd-java-agent:instrumentation:jacoco",
  ":dd-java-agent:instrumentation:jakarta-jms",
  ":dd-java-agent:instrumentation:jakarta-mail",
  ":dd-java-agent:instrumentation:java:java-concurrent:java-concurrent-1.8",
  ":dd-java-agent:instrumentation:java:java-concurrent:java-concurrent-21.0",
  ":dd-java-agent:instrumentation:java:java-concurrent:java-concurrent-25.0",
  ":dd-java-agent:instrumentation:java:java-io-1.8",
  ":dd-java-agent:instrumentation:java:java-lang:java-lang-1.8",
  ":dd-java-agent:instrumentation:java:java-lang:java-lang-11.0",
  ":dd-java-agent:instrumentation:java:java-lang:java-lang-15.0",
  ":dd-java-agent:instrumentation:java:java-lang:java-lang-17.0",
  ":dd-java-agent:instrumentation:java:java-lang:java-lang-21.0",
  ":dd-java-agent:instrumentation:java:java-lang:java-lang-9.0",
  ":dd-java-agent:instrumentation:java:java-net:java-net-1.8",
  ":dd-java-agent:instrumentation:java:java-net:java-net-11.0",
  ":dd-java-agent:instrumentation:java:java-nio-1.8",
  ":dd-java-agent:instrumentation:java:java-security-1.8",
  ":dd-java-agent:instrumentation:java:java-util-1.8",
  ":dd-java-agent:instrumentation:javax-mail-1.4.4",
  ":dd-java-agent:instrumentation:javax-naming-1.0",
  ":dd-java-agent:instrumentation:javax-xml",
  ":dd-java-agent:instrumentation:jboss:jboss-logmanager-1.1",
  ":dd-java-agent:instrumentation:jboss:jboss-modules-1.3",
  ":dd-java-agent:instrumentation:jdbc:scalikejdbc",
  ":dd-java-agent:instrumentation:jdbc",
  ":dd-java-agent:instrumentation:jedis:jedis-1.4",
  ":dd-java-agent:instrumentation:jedis:jedis-3.0",
  ":dd-java-agent:instrumentation:jedis:jedis-4.0",
  ":dd-java-agent:instrumentation:jersey:jersey-2.0",
  ":dd-java-agent:instrumentation:jersey:jersey-appsec:jersey-appsec-2.0",
  ":dd-java-agent:instrumentation:jersey:jersey-appsec:jersey-appsec-3.0",
  ":dd-java-agent:instrumentation:jersey:jersey-client-2.0",
  ":dd-java-agent:instrumentation:jetty:jetty-appsec:jetty-appsec-7.0",
  ":dd-java-agent:instrumentation:jetty:jetty-appsec:jetty-appsec-8.1.3",
  ":dd-java-agent:instrumentation:jetty:jetty-appsec:jetty-appsec-9.2",
  ":dd-java-agent:instrumentation:jetty:jetty-appsec:jetty-appsec-9.3",
  ":dd-java-agent:instrumentation:jetty:jetty-client:jetty-client-10.0",
  ":dd-java-agent:instrumentation:jetty:jetty-client:jetty-client-12.0",
  ":dd-java-agent:instrumentation:jetty:jetty-client:jetty-client-9.1",
  ":dd-java-agent:instrumentation:jetty:jetty-client:jetty-client-common",
  ":dd-java-agent:instrumentation:jetty:jetty-common",
  ":dd-java-agent:instrumentation:jetty:jetty-server:jetty-server-10.0",
  ":dd-java-agent:instrumentation:jetty:jetty-server:jetty-server-11.0",
  ":dd-java-agent:instrumentation:jetty:jetty-server:jetty-server-12.0",
  ":dd-java-agent:instrumentation:jetty:jetty-server:jetty-server-7.0",
  ":dd-java-agent:instrumentation:jetty:jetty-server:jetty-server-7.6",
  ":dd-java-agent:instrumentation:jetty:jetty-server:jetty-server-9.0.4",
  ":dd-java-agent:instrumentation:jetty:jetty-server:jetty-server-9.0",
  ":dd-java-agent:instrumentation:jetty:jetty-server:jetty-server-9.3",
  ":dd-java-agent:instrumentation:jetty:jetty-server:jetty-server-9.4.21",
  ":dd-java-agent:instrumentation:jetty:jetty-util-9.4.31",
  ":dd-java-agent:instrumentation:jms",
  ":dd-java-agent:instrumentation:jose-jwt",
  ":dd-java-agent:instrumentation:jsp-2.3",
  ":dd-java-agent:instrumentation:junit:junit-4.10:cucumber-junit-4",
  ":dd-java-agent:instrumentation:junit:junit-4.10:junit-4.13",
  ":dd-java-agent:instrumentation:junit:junit-4.10:munit-junit-4",
  ":dd-java-agent:instrumentation:junit:junit-4.10",
  ":dd-java-agent:instrumentation:junit:junit-5.3:cucumber-junit-5",
  ":dd-java-agent:instrumentation:junit:junit-5.3:junit-5.8",
  ":dd-java-agent:instrumentation:junit:junit-5.3:spock-junit-5",
  ":dd-java-agent:instrumentation:junit:junit-5.3",
  ":dd-java-agent:instrumentation:kafka:kafka-clients-0.11",
  ":dd-java-agent:instrumentation:kafka:kafka-clients-3.8",
  ":dd-java-agent:instrumentation:kafka:kafka-common",
  ":dd-java-agent:instrumentation:kafka:kafka-connect-0.11",
  ":dd-java-agent:instrumentation:kafka:kafka-streams-0.11",
  ":dd-java-agent:instrumentation:kafka:kafka-streams-1.0",
  ":dd-java-agent:instrumentation:karate",
  ":dd-java-agent:instrumentation:kotlin-coroutines",
  ":dd-java-agent:instrumentation:lettuce:lettuce-4.0",
  ":dd-java-agent:instrumentation:lettuce:lettuce-5.0",
  ":dd-java-agent:instrumentation:liberty:liberty-20.0",
  ":dd-java-agent:instrumentation:liberty:liberty-23.0",
  ":dd-java-agent:instrumentation:log4j:log4j-1.2.4",
  ":dd-java-agent:instrumentation:log4j:log4j-2.0",
  ":dd-java-agent:instrumentation:log4j:log4j-2.7",
  ":dd-java-agent:instrumentation:logback-1.0",
  ":dd-java-agent:instrumentation:maven:maven-3.2.1",
  ":dd-java-agent:instrumentation:maven:maven-surefire-3.0",
  ":dd-java-agent:instrumentation:micronaut:http-server-netty-2.0",
  ":dd-java-agent:instrumentation:micronaut:http-server-netty-3.0",
  ":dd-java-agent:instrumentation:micronaut:http-server-netty-4.0",
  ":dd-java-agent:instrumentation:micronaut",
  ":dd-java-agent:instrumentation:mongo:bson-document",
  ":dd-java-agent:instrumentation:mongo:common",
  ":dd-java-agent:instrumentation:mongo:driver-3.1-core-test",
  ":dd-java-agent:instrumentation:mongo:driver-3.1",
  ":dd-java-agent:instrumentation:mongo:driver-3.10-sync-test",
  ":dd-java-agent:instrumentation:mongo:driver-3.3-async-test",
  ":dd-java-agent:instrumentation:mongo:driver-3.4",
  ":dd-java-agent:instrumentation:mongo:driver-3.7-core-test",
  ":dd-java-agent:instrumentation:mongo:driver-4.0",
  ":dd-java-agent:instrumentation:mongo",
  ":dd-java-agent:instrumentation:mule-4.5",
  ":dd-java-agent:instrumentation:netty:netty-3.8",
  ":dd-java-agent:instrumentation:netty:netty-4.0",
  ":dd-java-agent:instrumentation:netty:netty-4.1",
  ":dd-java-agent:instrumentation:netty:netty-buffer-4.0",
  ":dd-java-agent:instrumentation:netty:netty-common",
  ":dd-java-agent:instrumentation:netty:netty-concurrent-4.0",
  ":dd-java-agent:instrumentation:netty:netty-promise-4.0",
  ":dd-java-agent:instrumentation:ognl-appsec",
  ":dd-java-agent:instrumentation:okhttp:okhttp-2.2",
  ":dd-java-agent:instrumentation:okhttp:okhttp-3.0",
  ":dd-java-agent:instrumentation:opensearch:rest",
  ":dd-java-agent:instrumentation:opensearch:transport",
  ":dd-java-agent:instrumentation:opensearch",
  ":dd-java-agent:instrumentation:opentelemetry:opentelemetry-0.3",
  ":dd-java-agent:instrumentation:opentelemetry:opentelemetry-1.4",
  ":dd-java-agent:instrumentation:opentelemetry:opentelemetry-1.47",
  ":dd-java-agent:instrumentation:opentelemetry:opentelemetry-annotations-1.20",
  ":dd-java-agent:instrumentation:opentelemetry:opentelemetry-annotations-1.26",
  ":dd-java-agent:instrumentation:opentracing:api-0.31",
  ":dd-java-agent:instrumentation:opentracing:api-0.32",
  ":dd-java-agent:instrumentation:opentracing",
  ":dd-java-agent:instrumentation:org-json-20230227",
  ":dd-java-agent:instrumentation:osgi-4.3",
  ":dd-java-agent:instrumentation:owasp-esapi-2",
  ":dd-java-agent:instrumentation:pekko:pekko-concurrent-1.0",
  ":dd-java-agent:instrumentation:pekko:pekko-http-1.0",
  ":dd-java-agent:instrumentation:play-ws:play-ws-1.0",
  ":dd-java-agent:instrumentation:play-ws:play-ws-2.0",
  ":dd-java-agent:instrumentation:play-ws:play-ws-2.1",
  ":dd-java-agent:instrumentation:play-ws:play-ws-common",
  ":dd-java-agent:instrumentation:play:play-2.3",
  ":dd-java-agent:instrumentation:play:play-2.4",
  ":dd-java-agent:instrumentation:play:play-2.6",
  ":dd-java-agent:instrumentation:protobuf-3.0",
  ":dd-java-agent:instrumentation:quartz-2.0",
  ":dd-java-agent:instrumentation:rabbitmq-amqp-2.7",
  ":dd-java-agent:instrumentation:ratpack-1.5",
  ":dd-java-agent:instrumentation:reactive-streams",
  ":dd-java-agent:instrumentation:reactor-core-3.1",
  ":dd-java-agent:instrumentation:reactor-netty-1",
  ":dd-java-agent:instrumentation:rediscala-1.8",
  ":dd-java-agent:instrumentation:redisson:redisson-2.0.0",
  ":dd-java-agent:instrumentation:redisson:redisson-2.3.0",
  ":dd-java-agent:instrumentation:redisson:redisson-3.10.3",
  ":dd-java-agent:instrumentation:redisson",
  ":dd-java-agent:instrumentation:renaissance",
  ":dd-java-agent:instrumentation:resilience4j:resilience4j-2.0",
  ":dd-java-agent:instrumentation:resilience4j:resilience4j-reactor-2.0",
  ":dd-java-agent:instrumentation:resteasy:resteasy-3.0",
  ":dd-java-agent:instrumentation:resteasy:resteasy-appsec-3.0",
  ":dd-java-agent:instrumentation:restlet-2.2",
  ":dd-java-agent:instrumentation:rmi",
  ":dd-java-agent:instrumentation:rs:jakarta-rs-annotations-3",
  ":dd-java-agent:instrumentation:rs:jax-rs:jax-rs-annotations:jax-rs-annotations-1",
  ":dd-java-agent:instrumentation:rs:jax-rs:jax-rs-annotations:jax-rs-annotations-2:filter-jersey",
  ":dd-java-agent:instrumentation:rs:jax-rs:jax-rs-annotations:jax-rs-annotations-2:filter-resteasy-3.0",
  ":dd-java-agent:instrumentation:rs:jax-rs:jax-rs-annotations:jax-rs-annotations-2:filter-resteasy-3.1",
  ":dd-java-agent:instrumentation:rs:jax-rs:jax-rs-annotations:jax-rs-annotations-2",
  ":dd-java-agent:instrumentation:rs:jax-rs:jax-rs-client:jax-rs-client-1.1",
  ":dd-java-agent:instrumentation:rs:jax-rs:jax-rs-client:jax-rs-client-2.0",
  ":dd-java-agent:instrumentation:rxjava:rxjava-1.0",
  ":dd-java-agent:instrumentation:rxjava:rxjava-2.0",
  ":dd-java-agent:instrumentation:scala:scala-concurrent-2.8",
  ":dd-java-agent:instrumentation:scala:scala-promise:scala-promise-2.10",
  ":dd-java-agent:instrumentation:scala:scala-promise:scala-promise-2.13",
  ":dd-java-agent:instrumentation:scala:scala-promise:scala-promise-common",
  ":dd-java-agent:instrumentation:scala:scala-2.10.7",
  ":dd-java-agent:instrumentation:scalatest-3.0.8",
  ":dd-java-agent:instrumentation:selenium-3.13",
  ":dd-java-agent:instrumentation:servicetalk:servicetalk-0.42.0",
  ":dd-java-agent:instrumentation:servicetalk:servicetalk-0.42.56",
  ":dd-java-agent:instrumentation:servicetalk",
  ":dd-java-agent:instrumentation:servlet:jakarta-servlet-5.0",
  ":dd-java-agent:instrumentation:servlet:javax-servlet:javax-servlet-2.2",
  ":dd-java-agent:instrumentation:servlet:javax-servlet:javax-servlet-3.0",
  ":dd-java-agent:instrumentation:servlet:javax-servlet:javax-servlet-common",
  ":dd-java-agent:instrumentation:servlet:javax-servlet:javax-servlet-iast",
  ":dd-java-agent:instrumentation:slick-3.2",
  ":dd-java-agent:instrumentation:snakeyaml-1.33",
  ":dd-java-agent:instrumentation:span-origin",
  ":dd-java-agent:instrumentation:spark-executor",
  ":dd-java-agent:instrumentation:spark:spark_2.12",
  ":dd-java-agent:instrumentation:spark:spark_2.13",
  ":dd-java-agent:instrumentation:sparkjava-2.3",
  ":dd-java-agent:instrumentation:spray-1.3",
  ":dd-java-agent:instrumentation:spring:spring-beans-3.1",
  ":dd-java-agent:instrumentation:spring:spring-boot-1.3",
  ":dd-java-agent:instrumentation:spring:spring-cloud-zuul-2.0",
  ":dd-java-agent:instrumentation:spring:spring-core-3.2.2",
  ":dd-java-agent:instrumentation:spring:spring-data-1.8",
  ":dd-java-agent:instrumentation:spring:spring-jms-3.1",
  ":dd-java-agent:instrumentation:spring:spring-messaging-4.0",
  ":dd-java-agent:instrumentation:spring:spring-rabbit-1.5",
  ":dd-java-agent:instrumentation:spring:spring-scheduling-3.1",
  ":dd-java-agent:instrumentation:spring:spring-security:spring-security-5.0",
  ":dd-java-agent:instrumentation:spring:spring-security:spring-security-6.0",
  ":dd-java-agent:instrumentation:spring:spring-webflux:spring-webflux-5.0",
  ":dd-java-agent:instrumentation:spring:spring-webflux:spring-webflux-6.0",
  ":dd-java-agent:instrumentation:spring:spring-webmvc:spring-webmvc-3.1",
  ":dd-java-agent:instrumentation:spring:spring-webmvc:spring-webmvc-5.3",
  ":dd-java-agent:instrumentation:spring:spring-webmvc:spring-webmvc-6.0",
  ":dd-java-agent:instrumentation:spring:spring-ws-2.0",
  ":dd-java-agent:instrumentation:spymemcached-2.10",
  ":dd-java-agent:instrumentation:sslsocket",
  ":dd-java-agent:instrumentation:synapse-3.0",
  ":dd-java-agent:instrumentation:testng:testng-6",
  ":dd-java-agent:instrumentation:testng:testng-7",
  ":dd-java-agent:instrumentation:testng",
  ":dd-java-agent:instrumentation:thymeleaf",
  ":dd-java-agent:instrumentation:tibco-businessworks:tibco-businessworks-5.14",
  ":dd-java-agent:instrumentation:tibco-businessworks:tibco-businessworks-6.5",
  ":dd-java-agent:instrumentation:tibco-businessworks:tibco-businessworks-stubs",
  ":dd-java-agent:instrumentation:tibco-businessworks",
  ":dd-java-agent:instrumentation:tinylog-2.0",
  ":dd-java-agent:instrumentation:tomcat:tomcat-5.5",
  ":dd-java-agent:instrumentation:tomcat:tomcat-9.0",
  ":dd-java-agent:instrumentation:tomcat:tomcat-appsec:tomcat-appsec-5.5",
  ":dd-java-agent:instrumentation:tomcat:tomcat-appsec:tomcat-appsec-6.0",
  ":dd-java-agent:instrumentation:tomcat:tomcat-appsec:tomcat-appsec-7.0",
  ":dd-java-agent:instrumentation:tomcat:tomcat-common",
  ":dd-java-agent:instrumentation:trace-annotation",
  ":dd-java-agent:instrumentation:twilio-0.0.1",
  ":dd-java-agent:instrumentation:unbescape-1.1",
  ":dd-java-agent:instrumentation:undertow:undertow-2.0",
  ":dd-java-agent:instrumentation:undertow:undertow-2.2",
  ":dd-java-agent:instrumentation:undertow",
  ":dd-java-agent:instrumentation:valkey-java-5.3",
  ":dd-java-agent:instrumentation:velocity-1.5",
  ":dd-java-agent:instrumentation:vertx:vertx-mysql-client:vertx-mysql-client-3.9",
  ":dd-java-agent:instrumentation:vertx:vertx-mysql-client:vertx-mysql-client-4.0",
  ":dd-java-agent:instrumentation:vertx:vertx-mysql-client:vertx-mysql-client-4.4.2",
  ":dd-java-agent:instrumentation:vertx:vertx-pg-client:vertx-pg-client-4.0",
  ":dd-java-agent:instrumentation:vertx:vertx-pg-client:vertx-pg-client-4.4.2",
  ":dd-java-agent:instrumentation:vertx:vertx-redis-client-3.9:stubs",
  ":dd-java-agent:instrumentation:vertx:vertx-redis-client-3.9",
  ":dd-java-agent:instrumentation:vertx:vertx-rx-3.5",
  ":dd-java-agent:instrumentation:vertx:vertx-sql-client-3.9",
  ":dd-java-agent:instrumentation:vertx:vertx-web:vertx-web-3.4",
  ":dd-java-agent:instrumentation:vertx:vertx-web:vertx-web-3.5",
  ":dd-java-agent:instrumentation:vertx:vertx-web:vertx-web-3.9",
  ":dd-java-agent:instrumentation:vertx:vertx-web:vertx-web-4.0",
  ":dd-java-agent:instrumentation:vertx:vertx-web:vertx-web-5.0",
  ":dd-java-agent:instrumentation:weaver",
  ":dd-java-agent:instrumentation:websocket:jakarta-websocket-2.0",
  ":dd-java-agent:instrumentation:websocket:javax-websocket-1.0",
  ":dd-java-agent:instrumentation:websocket:jetty-websocket:jetty-websocket-10",
  ":dd-java-agent:instrumentation:websocket:jetty-websocket:jetty-websocket-11",
  ":dd-java-agent:instrumentation:websocket:jetty-websocket:jetty-websocket-12",
  ":dd-java-agent:instrumentation:websocket:jetty-websocket",
  ":dd-java-agent:instrumentation:websphere-jmx-8.5",
  ":dd-java-agent:instrumentation:wildfly-9.0",
  ":dd-java-agent:instrumentation:ws:jakarta-ws-annotations-3.0",
  ":dd-java-agent:instrumentation:ws:jax-ws:jax-ws-annotations-1.1",
  ":dd-java-agent:instrumentation:ws:jax-ws:jax-ws-annotations-2.0",
  ":dd-java-agent:instrumentation:zio:zio-2.0",
)

// Optional `akka-http-10.6` instrumentation (see BUILDING.md for how to enable it):
if (providers.gradleProperty("akkaRepositoryToken").isPresent) {
  include(
    ":dd-java-agent:instrumentation:akka:akka-http:akka-http-10.6"
  )
} else {
  logger.quiet("Omitting :dd-java-agent:instrumentation:akka:akka-http:akka-http-10.6: 'akkaRepositoryToken' not configured")
}

// benchmark
include(
  ":dd-java-agent:benchmark",
  ":dd-java-agent:benchmark-integration",
  ":dd-java-agent:benchmark-integration:jetty-perftest",
  ":dd-java-agent:benchmark-integration:play-perftest",
)
