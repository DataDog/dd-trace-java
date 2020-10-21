import scala.concurrent.Future
import scala.concurrent.Promise

class Scala213PromiseThreadPoolTest extends Scala213PromiseTest {

  @Override
  Future<String> map(Promise<Boolean> promise, Closure<String> callback) {
    return PromiseUtils.mapInThreadPool(promise, callback) as Future<String>
  }
}
