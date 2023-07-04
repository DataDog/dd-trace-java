package datadog.trace.instrumentation.springsecurity5

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.core.DDSpan
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.instrumentation.springsecurity5.TestEndpoint.SUCCESS
import static datadog.trace.instrumentation.springsecurity5.TestEndpoint.UNKNOWN
import static datadog.trace.instrumentation.springsecurity5.TestEndpoint.NOT_FOUND


class SpringBootBasedTest extends WithHttpServer<ConfigurableApplicationContext> {

    @Shared
    def context

    class SpringBootServer implements HttpServer {
        def port = 0
        final app = new SpringApplication(AppConfig, TestController)

        @Override
        void start() {
            app.setDefaultProperties(["server.port": 0, "server.context-path": "/"])
            context = app.run()
            port = (context as ServletWebServerApplicationContext).webServer.port
            assert port > 0
        }

        @Override
        void stop() {
            context.close()
        }

        @Override
        URI address() {
            return new URI("http://localhost:$port/")
        }

        @Override
        String toString() {
            return this.class.name
        }
    }

    @Override
    HttpServer server() {
        return new SpringBootServer()
    }

    @Override
    protected void configurePreAgent() {
        super.configurePreAgent()
        injectSysConfig('dd.appsec.enabled', 'true')
    }

    @Override
    int version() {
        return 0
    }

    @Override
    String service() {
        return null
    }

    @Override
    String operation() {
        return null
    }

    def setupSpec() {
        server = server()
        server.start()
        address = server.address()
        assert address.port > 0
        assert address.path.endsWith("/")
        println "$server started at: $address"
    }

    Request.Builder request(TestEndpoint uri, String method, RequestBody body) {
        def url = HttpUrl.get(uri.resolve(address)).newBuilder()
                .encodedQuery(uri.rawQuery)
                .fragment(uri.fragment)
                .build()
        return new Request.Builder()
                .url(url)
                .method(method, body)
    }

    static <T> T controller(TestEndpoint endpoint, Closure<T> closure) {
        if (endpoint == NOT_FOUND || endpoint == UNKNOWN) {
            return closure()
        }
        return runUnderTrace("controller", closure)
    }

    def "test success with request"() {
        setup:
        def request = request(SUCCESS, 'GET', null).build()

        when:
        def response = client.newCall(request).execute()
        TEST_WRITER.waitForTraces(1)
        DDSpan span = TEST_WRITER.flatten().first()

        then:
        response.code() == SUCCESS.status
        response.body().string() == SUCCESS.body
        !span.getTags().isEmpty()
    }
}
