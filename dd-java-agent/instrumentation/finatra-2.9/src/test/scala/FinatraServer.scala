import com.twitter.finagle.http.Request
import com.twitter.finatra.http.HttpServer
import com.twitter.finatra.http.filters.ExceptionMappingFilter
import com.twitter.finatra.http.routing.HttpRouter

import java.util.concurrent.{CountDownLatch, TimeUnit}

class FinatraServer extends HttpServer {

  private val latch = new CountDownLatch(1)

  override protected def configureHttp(router: HttpRouter): Unit = {
    router
      .filter[ExceptionMappingFilter[Request]]
      .add[FinatraController]
      .exceptionMapper[ResponseSettingExceptionMapper]
  }

  override protected def postWarmup(): Unit = {
    super.postWarmup()
    latch.countDown()
  }

  def awaitStart(timeout: Long, unit: TimeUnit): Unit = {
    latch.await(timeout, unit)
  }
}
