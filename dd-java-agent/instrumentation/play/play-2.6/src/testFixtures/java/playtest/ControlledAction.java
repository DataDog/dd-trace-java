package playtest;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import akka.util.ByteString;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayDeque;
import java.util.Queue;
import play.api.Logger;
import play.api.libs.streams.Accumulator;
import play.api.mvc.Action;
import play.api.mvc.BodyParser;
import play.api.mvc.Request;
import play.api.mvc.RequestHeader;
import play.api.mvc.Result;
import play.mvc.Http;
import play.mvc.Results;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.Promise;
import scala.concurrent.Promise$;
import scala.runtime.AbstractFunction1;
import scala.util.Failure;
import scala.util.Success;
import scala.util.Try;

/** An action whose completion callback runs only when the test drains its execution context. */
public class ControlledAction
    extends AbstractFunction1<RequestHeader, Accumulator<ByteString, Result>>
    implements Action<Http.RequestBody>, ExecutionContext {
  private final Queue<Runnable> tasks = new ArrayDeque<>();
  private final Promise<Result> promise = Promise$.MODULE$.apply();
  public final Try<Result> completion;
  public AgentSpan span;

  public ControlledAction(int status, boolean completed) {
    completion =
        status == 0
            ? new Failure<>(new IllegalStateException("action failed"))
            : new Success<>(Results.status(status).asScala());
    if (completed) {
      complete();
    }
  }

  @Override
  public Future<Result> apply(Request<Http.RequestBody> request) {
    span = activeSpan();
    startSpan("test", "action").finish();
    return promise.future();
  }

  public Future<Result> invoke() {
    return apply(new Http.RequestBuilder().method("GET").uri("/completion").build().asScala());
  }

  public void complete() {
    promise.tryComplete(completion);
  }

  public void drain() {
    while (!tasks.isEmpty()) {
      tasks.remove().run();
    }
  }

  @Override
  public void execute(Runnable task) {
    tasks.add(task);
  }

  @Override
  public void reportFailure(Throwable error) {
    throw new AssertionError(error);
  }

  @Override
  public ExecutionContext prepare() {
    return this;
  }

  @Override
  public ExecutionContext executionContext() {
    return this;
  }

  @Override
  public Action<Http.RequestBody> apply() {
    return this;
  }

  @Override
  public play.mvc.EssentialAction asJava() {
    throw new UnsupportedOperationException("The test invokes the Scala action directly");
  }

  @Override
  public Accumulator<ByteString, Result> apply(RequestHeader request) {
    throw new UnsupportedOperationException("Use the parsed request overload");
  }

  @Override
  public BodyParser<Http.RequestBody> parser() {
    throw new UnsupportedOperationException("The request is already parsed");
  }

  @Override
  public Logger play$api$mvc$Action$$logger() {
    throw new UnsupportedOperationException(
        "The action does not use the Scala trait implementation");
  }
}
