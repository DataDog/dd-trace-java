import scala.concurrent.Future
import scala.concurrent.Promise

class Scala213ForkJoinPoolPromiseTest extends Scala213PromiseTest {


  @Override
  Future<String> map(Promise<Boolean> promise, Closure<String> callback) {
    return PromiseUtils.mapInForkJoinPool(promise, callback) as Future<String>
  }
}
