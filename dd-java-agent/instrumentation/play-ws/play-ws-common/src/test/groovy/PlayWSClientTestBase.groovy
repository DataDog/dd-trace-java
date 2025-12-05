import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import datadog.trace.agent.test.asserts.TagsAssert
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.playws.PlayWSClientDecorator
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClient
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClientConfig
import play.shaded.ahc.org.asynchttpclient.DefaultAsyncHttpClient
import play.shaded.ahc.org.asynchttpclient.DefaultAsyncHttpClientConfig
import spock.lang.Shared

import java.util.concurrent.TimeoutException

abstract class PlayWSClientTestBase extends HttpClientTest {
  @Shared
  ActorSystem system

  @Shared
  AsyncHttpClient asyncHttpClient

  @Shared
  ActorMaterializer materializer

  def setupSpec() {
    String name = "play-ws"
    system = ActorSystem.create(name)
    ActorMaterializerSettings settings = ActorMaterializerSettings.create(system)
    materializer = ActorMaterializer.create(settings, system, name)

    AsyncHttpClientConfig asyncHttpClientConfig =
      new DefaultAsyncHttpClientConfig.Builder()
      .setMaxRequestRetry(0)
      .setShutdownQuietPeriod(0)
      .setShutdownTimeout(0)
      .build()

    asyncHttpClient = new DefaultAsyncHttpClient(asyncHttpClientConfig)
  }

  def cleanupSpec() {
    system?.terminate()
  }

  @Override
  CharSequence component() {
    return PlayWSClientDecorator.DECORATE.component()
  }

  @Override
  boolean testCircularRedirects() {
    return false
  }

  @Override
  boolean testCallbackWithParent() {
    return false
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
    return "play-ws.request"
  }

  protected void runInAdHocThread(Closure callback) {
    if (callback != null) {
      // Execute callback in a separate thread to clear trace context
      def thread = new Thread({ callback.call() })
      thread.start()
      thread.join()
    }
  }

  @Override
  void assertErrorTags(TagsAssert tagsAssert, Throwable exception) {
    // PlayWS classes throw different exception types for the same connection failures
    if (exception instanceof ConnectException ||
      exception instanceof SocketTimeoutException ||
      exception instanceof TimeoutException) {
      tagsAssert.tag("error.type", {
        String actualType = it as String
        return actualType == "java.net.ConnectException" ||
          actualType == "java.net.SocketTimeoutException" ||
          actualType == "java.util.concurrent.TimeoutException"
      })
      tagsAssert.tag("error.stack", String)
      tagsAssert.tag("error.message", String)
    } else {
      super.assertErrorTags(tagsAssert, exception)
    }
  }
}
