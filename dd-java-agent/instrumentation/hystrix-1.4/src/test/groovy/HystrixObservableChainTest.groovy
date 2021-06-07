import com.netflix.hystrix.HystrixObservableCommand
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.Tags
import rx.Observable
import rx.schedulers.Schedulers

import static com.netflix.hystrix.HystrixCommandGroupKey.Factory.asKey
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class HystrixObservableChainTest extends HystrixTestRunner {

  @Override
  boolean useStrictTraceWrites() {
    // FIXME - test still times out in CI
    return false
  }

  def "test command #action"() {
    setup:

    def result = runUnderTrace("parent") {
      def val = new HystrixObservableCommand<String>(asKey("ExampleGroup")) {
          @Trace
          private String tracedMethod() {
            return "Hello"
          }

          @Override
          protected Observable<String> construct() {
            Observable.defer {
              Observable.just(tracedMethod())
            }
            .subscribeOn(Schedulers.immediate())
          }
        }.toObservable()
        .subscribeOn(Schedulers.io())
        .map {
          it.toUpperCase()
        }.flatMap { str ->
          new HystrixObservableCommand<String>(asKey("OtherGroup")) {
              @Trace
              private String tracedMethod() {
                blockUntilChildSpansFinished(2)
                return "$str!"
              }

              @Override
              protected Observable<String> construct() {
                Observable.defer {
                  Observable.just(tracedMethod())
                }
                .subscribeOn(Schedulers.computation())
              }
            }.toObservable()
            .subscribeOn(Schedulers.trampoline())
        }.toBlocking().first()
      // when this is running in different threads, we don't know when the other span is done
      // adding sleep to improve ordering consistency
      blockUntilChildSpansFinished(4)
      return val
    }

    expect:
    result == "HELLO!"

    assertTraces(1) {
      trace(5) {
        span {
          operationName "parent"
          resourceName "parent"
          spanType null
          parent()
          errored false
          tags {
            defaultTags()
          }
        }
        span {
          operationName "hystrix.cmd"
          resourceName "OtherGroup.HystrixObservableChainTest\$2.execute"
          spanType null
          childOf span(3)
          errored false
          tags {
            "$Tags.COMPONENT" "hystrix"
            "hystrix.command" "HystrixObservableChainTest\$2"
            "hystrix.group" "OtherGroup"
            "hystrix.circuit-open" false
            defaultTags()
          }
        }
        span {
          operationName "trace.annotation"
          resourceName "HystrixObservableChainTest\$2.tracedMethod"
          spanType null
          childOf span(1)
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          operationName "hystrix.cmd"
          resourceName "ExampleGroup.HystrixObservableChainTest\$1.execute"
          spanType null
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "hystrix"
            "hystrix.command" "HystrixObservableChainTest\$1"
            "hystrix.group" "ExampleGroup"
            "hystrix.circuit-open" false
            defaultTags()
          }
        }
        span {
          operationName "trace.annotation"
          resourceName "HystrixObservableChainTest\$1.tracedMethod"
          spanType null
          childOf span(3)
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }
}
