import scala.concurrent.Future
import scala.concurrent.Promise

class Scala213PromiseScheduledThreadPoolTest extends Scala213PromiseTest {

  @Override
  Future<String> map(Promise<Boolean> promise, Closure<String> callback) {
    return PromiseUtils.mapInScheduledThreadPool(promise, callback) as Future<String>
  }
}
