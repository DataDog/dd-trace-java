package datadog.trace.lambda


import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.http.OkHttpUtils
import datadog.trace.api.Config
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.bootstrap.instrumentation.api.StatsPoint
import datadog.trace.core.test.DDCoreSpecification
import okhttp3.HttpUrl
import okio.BufferedSource
import okio.GzipSource
import okio.Okio
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import spock.lang.AutoCleanup
import spock.lang.Requires
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer.DEFAULT_BUCKET_DURATION_MILLIS
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.SECONDS

class LambdaHandlerTest extends DDCoreSpecification {

    class TestObject {

        public String field1
        public boolean field2

        public TestObject() {
            this.field1 = "toto"
            this.field2 = true
        }
    }

    def "test start invocation success"() {
        given:
        def server = httpServer {
            handlers {
                post("/lambda/start-invocation") {
                    response
                    .status(200)
                    .addHeader("x-datadog-trace-id", "1234")
                    .addHeader("x-datadog-span-id", "5678")
                    .send()
                }
            }
        }
        def testHttpClient = OkHttpUtils.buildHttpClient(HttpUrl.get(server.address), 5000L)
        LambdaHandler.setAgentUrl(server.address.toString())

        when:
        def objTest = LambdaHandler.notifyStartInvocation(testHttpClient, obj)
        
        then:
        objTest.getTraceId().toString() == traceId
        objTest.getSpanId().toString() == spanId

        where:
        traceId    | spanId      | obj
        "1234"     | "5678"      | new TestObject()
    }

    def "test start invocation failure"() {
        given:
        def server = httpServer {
            handlers {
                post("/lambda/start-invocation") {
                    response
                    .status(500)
                    .send()
                }
            }
        }
        def testHttpClient = OkHttpUtils.buildHttpClient(HttpUrl.get(server.address), 5000L)
        LambdaHandler.setAgentUrl(server.address.toString())

        when:
        def objTest = LambdaHandler.notifyStartInvocation(testHttpClient, obj)
        
        then:
        objTest == expected

        where:
        expected    | obj
        null        | new TestObject()
    }

    def "test end invocation success"() {
        given:
        def server = httpServer {
            handlers {
                post("/lambda/end-invocation") {
                    response
                    .status(200)
                    .send()
                }
            }
        }
        def testHttpClient = OkHttpUtils.buildHttpClient(HttpUrl.get(server.address), 5000L)
        LambdaHandler.setAgentUrl(server.address.toString())

        when:
        def result = LambdaHandler.notifyEndInvocation(testHttpClient, boolValue)
        server.lastRequest.headers.get("x-datadog-invocation-error") == headerValue
        
        then:
        result == expected

        where:
        expected  | headerValue     | boolValue
        true      | "true"          | true
        true      | null            | false
    }

    def "test end invocation failure"() {
        given:
        def server = httpServer {
            handlers {
                post("/lambda/end-invocation") {
                    response
                    .status(500)
                    .send()
                }
            }
        }
        def testHttpClient = OkHttpUtils.buildHttpClient(HttpUrl.get(server.address), 5000L)
        LambdaHandler.setAgentUrl(server.address.toString())

        when:
        def result = LambdaHandler.notifyEndInvocation(testHttpClient, boolValue)
        
        then:
        result == expected
        server.lastRequest.headers.get("x-datadog-invocation-error") == headerValue

        where:
        expected  | headerValue     | boolValue
        false     | "true"          | true
        false     | null            | false
    }
}