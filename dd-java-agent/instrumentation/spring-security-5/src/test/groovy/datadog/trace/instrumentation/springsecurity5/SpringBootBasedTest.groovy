package datadog.trace.instrumentation.springsecurity5

import com.datadog.appsec.AppSecHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.core.DDSpan
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Shared

import static datadog.trace.instrumentation.springsecurity5.TestEndpoint.LOGIN
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.instrumentation.springsecurity5.TestEndpoint.REGISTER
import static datadog.trace.instrumentation.springsecurity5.TestEndpoint.UNKNOWN
import static datadog.trace.instrumentation.springsecurity5.TestEndpoint.NOT_FOUND


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
    injectSysConfig('dd.appsec.automated-user-events-tracking', 'extended')
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
    span.getTag("appsec.events.users.signup.track") == true
    span.getTag("_dd.appsec.events.users.signup.auto.mode") == 'EXTENDED'
    span.getTag("usr.id") == 'admin'
    span.getTag("appsec.events.users.signup")['enabled'] == 'true'
    span.getTag("appsec.events.users.signup")['authorities'] == 'ROLE_USER'
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
    span.getTag("appsec.events.users.login.failure.track") == true
    span.getTag("_dd.appsec.events.users.login.failure.auto.mode") == 'EXTENDED'
    span.getTag("appsec.events.users.login.failure.usr.exists") == false
    span.getTag("appsec.events.users.login.failure.usr.id") == 'not_existing_user'
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
    span.getTag("appsec.events.users.login.failure.track") == true
    span.getTag("_dd.appsec.events.users.login.failure.auto.mode") == 'EXTENDED'
    // TODO: Ideally should be `false` but we have no reliable method to detect it it is just absent. See APPSEC-12765.
    span.getTag("appsec.events.users.login.failure.usr.exists") == null
    span.getTag("appsec.events.users.login.failure.usr.id") == 'admin'
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
    span.getTag("appsec.events.users.login.success.track") == true
    span.getTag("_dd.appsec.events.users.login.success.auto.mode") == 'EXTENDED'
    span.getTag("usr.id") == 'admin'
    span.getTag("appsec.events.users.login.success")['credentialsNonExpired'] == 'true'
    span.getTag("appsec.events.users.login.success")['accountNonExpired'] == 'true'
    span.getTag("appsec.events.users.login.success")['enabled'] == 'true'
    span.getTag("appsec.events.users.login.success")['authorities'] == 'ROLE_USER'
    span.getTag("appsec.events.users.login.success")['accountNonLocked'] == 'true'
  }

  void 'test failed signup'() {
    setup:
    final formBody = new FormBody.Builder()
      .add('username', randomString(1_000))
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

  @SuppressWarnings('GroovyAssignabilityCheck')
  private static String randomString(int length) {
    return new Random().with { random ->
      (1..length).collect { Character.valueOf((char) (random.nextInt(26) + (char)'a')) }
    }.join()
  }
}
