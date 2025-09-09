package datadog.trace.instrumentation.springsecurity6

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.Appender
import com.datadog.appsec.AppSecHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.api.config.AppSecConfig
import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.springsecurity5.SpringSecurityUserEventDecorator
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Shared

import static datadog.trace.agent.test.utils.OkHttpUtils.clientBuilder
import static datadog.trace.agent.test.utils.OkHttpUtils.cookieJar
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.instrumentation.springsecurity6.TestEndpoint.CUSTOM
import static datadog.trace.instrumentation.springsecurity6.TestEndpoint.LOGIN
import static datadog.trace.instrumentation.springsecurity6.TestEndpoint.NOT_FOUND
import static datadog.trace.instrumentation.springsecurity6.TestEndpoint.REGISTER
import static datadog.trace.instrumentation.springsecurity6.TestEndpoint.SUCCESS
import static datadog.trace.instrumentation.springsecurity6.TestEndpoint.UNKNOWN

class SpringBootBasedTest extends AppSecHttpServerTest<ConfigurableApplicationContext> {

  @Shared
  def context

  SpringApplication application() {
    return new SpringApplication(AppConfig, UserController, SecurityConfig)
  }

  class SpringBootServer implements HttpServer {
    def port = 0
    final app = application()

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

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(AppSecConfig.APPSEC_AUTO_USER_INSTRUMENTATION_MODE, 'identification')
  }

  Request.Builder request(TestEndpoint uri, String method, RequestBody body) {
    def url = HttpUrl.get(uri.resolve(address)).newBuilder()
      .encodedQuery(uri.rawQuery)
      .fragment(uri.fragment)
      .build()
    return new Request.Builder()
      .url(url)
      //.addHeader('user-agent', 'Arachni/v1')
      .method(method, body)
  }

  static <T> T controller(TestEndpoint endpoint, Closure<T> closure) {
    if (endpoint == NOT_FOUND || endpoint == UNKNOWN) {
      return closure()
    }
    return runUnderTrace("controller", closure)
  }


  def "test signup event"() {
    setup:
    RequestBody formBody = new FormBody.Builder()
      .add("username", "admin")
      .add("password", "admin")
      .build()

    def request = request(REGISTER, "POST", formBody).build()

    when:
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER.flatten().first()

    then:
    response.code() == REGISTER.status
    response.body().string() == REGISTER.body
    !span.getTags().isEmpty()
    span.getTag('appsec.events.users.signup.usr.login') == 'admin'
    span.getTag('_dd.appsec.usr.login') == 'admin'
    span.getTag('_dd.appsec.events.users.signup.auto.mode') == 'identification'
    span.getTag('appsec.events.users.signup.track') == true
    span.getTag('appsec.events.users.signup')['enabled'] == 'true'
    span.getTag('appsec.events.users.signup')['authorities'] == 'ROLE_USER'
  }


  def "test failed login with non existing user"() {
    setup:
    RequestBody formBody = new FormBody.Builder()
      .add("username", "not_existing_user")
      .add("password", "some_password")
      .build()

    def request = request(LOGIN, "POST", formBody).build()

    when:
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER.flatten().first()

    then:
    response.code() == LOGIN.status
    response.body().string() == LOGIN.body
    !span.getTags().isEmpty()
    span.getTag('appsec.events.users.login.failure.usr.login') == 'not_existing_user'
    span.getTag('_dd.appsec.usr.login') == 'not_existing_user'
    span.getTag('_dd.appsec.events.users.login.failure.auto.mode') == 'identification'
    span.getTag('appsec.events.users.login.failure.track') == true
    span.getTag('appsec.events.users.login.failure.usr.exists') == false
  }


  def "test failed login with existing user but wrong password"() {
    setup:
    RequestBody formBody = new FormBody.Builder()
      .add("username", "admin")
      .add("password", "wrong_password").build()

    def request = request(LOGIN, "POST", formBody).build()

    when:
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER.flatten().first()

    then:
    response.code() == LOGIN.status
    response.body().string() == LOGIN.body
    !span.getTags().isEmpty()
    span.getTag('appsec.events.users.login.failure.usr.login') == 'admin'
    span.getTag('_dd.appsec.usr.login') == 'admin'
    span.getTag('_dd.appsec.events.users.login.failure.auto.mode') == 'identification'
    span.getTag('appsec.events.users.login.failure.track') == true
    // TODO: Ideally should be `false` but we have no reliable method to detect it it is just absent. See APPSEC-12765.
    span.getTag('appsec.events.users.login.failure.usr.exists') == null
  }


  def "test success login"() {
    setup:
    RequestBody formBody = new FormBody.Builder()
      .add("username", "admin")
      .add("password", "admin")
      .build()

    def request = request(LOGIN, "POST", formBody).build()

    when:
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER.flatten().first()

    then:
    response.code() == LOGIN.status
    response.body().string() == LOGIN.body
    !span.getTags().isEmpty()
    span.getTag('appsec.events.users.login.success.usr.login') == 'admin'
    span.getTag('_dd.appsec.usr.login') == 'admin'
    span.getTag('_dd.appsec.events.users.login.success.auto.mode') == 'identification'
    span.getTag('appsec.events.users.login.success.track') == true
    span.getTag('appsec.events.users.login.success')['credentialsNonExpired'] == 'true'
    span.getTag('appsec.events.users.login.success')['accountNonExpired'] == 'true'
    span.getTag('appsec.events.users.login.success')['enabled'] == 'true'
    span.getTag('appsec.events.users.login.success')['authorities'] == 'ROLE_USER'
    span.getTag('appsec.events.users.login.success')['accountNonLocked'] == 'true'
  }

  void 'test failed signup'() {
    setup:
    final formBody = new FormBody.Builder()
      .add('username', 'cant_create_me')
      .add('password', 'cant_create_me')
      .build()

    final request = request(REGISTER, 'POST', formBody).build()

    when:
    final response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(1)
    final span = TEST_WRITER.flatten().first() as DDSpan

    then:
    response.code() == 500
    span.getTags().findAll { it.key.startsWith('appsec.events.users.signup') }.isEmpty()
  }

  void 'test skipped authentication'() {
    setup:
    final appender = Mock(Appender)
    final logger = SpringSecurityUserEventDecorator.LOGGER as Logger
    logger.addAppender(appender)

    and:
    final requestCount = 3
    final request = request(CUSTOM, "GET", null).addHeader('X-Custom-User', 'batman').build()

    when:
    final response = (1..requestCount).collect { client.newCall(request).execute() }.first()
    TEST_WRITER.waitForTraces(3)
    final span = TEST_WRITER.flatten().first() as DDSpan
    logger.detachAppender(appender) // cant add cleanup

    then:
    response.code() == CUSTOM.status
    span.context().resourceName.contains(CUSTOM.path)
    span.getTags().findAll { key, value -> key.startsWith('appsec.events.users.login')}.isEmpty()
    // single call to the appender
    1 * appender.doAppend(_) >> {
      assert it[0].toString().contains('Skipped authentication, auth=org.springframework.security.authentication.AbstractAuthenticationToken')
    }
    0 * appender._
  }

  void 'test user event'() {
    setup:
    def client = clientBuilder().cookieJar(cookieJar()).followRedirects(false).build()
    def formBody = new FormBody.Builder()
      .add("username", "admin")
      .add("password", "admin")
      .build()

    def loginRequest = request(LOGIN, "POST", formBody).build()
    def loginResponse = client.newCall(loginRequest).execute()
    assert loginResponse.code() == LOGIN.status
    assert loginResponse.body().string() == LOGIN.body
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.start() // clear all traces

    when:
    def request = request(SUCCESS, "GET", null).build()
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(1)
    def span = TEST_WRITER.flatten().first() as DDSpan

    then:
    response.code() == SUCCESS.status
    response.body().string() == SUCCESS.body
    span.getResourceName().toString() == 'GET /success'
    !span.getTags().isEmpty()
    span.getTag('usr.id') == 'admin'
    span.getTag('_dd.appsec.usr.id') == 'admin'
    span.getTag('_dd.appsec.user.collection_mode') == 'identification'
  }
}
