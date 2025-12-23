package datadog.trace.instrumentation.ratpack;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.http.StoredByteBody;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import ratpack.stream.TransformablePublisher;

public class RequestBodyCollectionPublisher implements TransformablePublisher<ByteBuf> {
  private final ByteBufIntoByteBufferCallback cb = new ByteBufIntoByteBufferCallback();
  private final StoredByteBody storedByteBody;
  private final Publisher<ByteBuf> input;

  public RequestBodyCollectionPublisher(StoredByteBody storedByteBody, Publisher<ByteBuf> input) {
    this.storedByteBody = storedByteBody;
    this.input = input;
  }

  @Override
  public void subscribe(final Subscriber<? super ByteBuf> outSubscriber) {
    input.subscribe(
        new Subscriber<ByteBuf>() {

          private final AtomicBoolean done = new AtomicBoolean();

          @Override
          public void onSubscribe(Subscription subscription) {
            outSubscriber.onSubscribe(subscription);
          }

          @Override
          public void onNext(ByteBuf in) {
            // duplicate in order not to move indexes. Release is unnecessary
            cb.byteBuf = in.duplicate();
            storedByteBody.appendData(cb, cb.byteBuf.readableBytes());
            if (!done.get()) {
              outSubscriber.onNext(in);
            }
          }

          @Override
          public void onError(Throwable t) {
            Flow<Void> flow = storedByteBody.maybeNotify();
            Flow.Action action = flow.getAction();
            if (action instanceof Flow.Action.RequestBlockingAction) {
              block((Flow.Action.RequestBlockingAction) action, t);
            } else if (done.compareAndSet(false, true)) {
              outSubscriber.onError(t);
            }
          }

          @Override
          public void onComplete() {
            Flow<Void> flow = storedByteBody.maybeNotify();
            Flow.Action action = flow.getAction();
            if (action instanceof Flow.Action.RequestBlockingAction) {
              block(
                  (Flow.Action.RequestBlockingAction) action,
                  new BlockingException("Blocked request (for RequestBody/readStream)"));
            } else if (done.compareAndSet(false, true)) {
              outSubscriber.onComplete();
            }
          }

          private void block(Flow.Action.RequestBlockingAction rba, Throwable t) {
            AgentSpan agentSpan = activeSpan();
            if (agentSpan == null) {
              return;
            }
            RequestContext requestContext = agentSpan.getRequestContext();
            BlockResponseFunction blockResponseFunction = requestContext.getBlockResponseFunction();
            if (blockResponseFunction == null) {
              return;
            }
            blockResponseFunction.tryCommitBlockingResponse(requestContext.getTraceSegment(), rba);

            // we can't directly interrupt user code here by throwing an exception
            // user code must listen for errors and implement its own logic to prevent
            // the request processing from continue (although we always commit the error response)
            if (done.compareAndSet(false, true)) {
              outSubscriber.onError(t);
            }
          }
        });
  }

  public static class ByteBufIntoByteBufferCallback
      implements StoredByteBody.ByteBufferWriteCallback {
    ByteBuf byteBuf;

    @Override
    public void put(ByteBuffer undecodedData) {
      byteBuf.readBytes(undecodedData);
    }
  }
}
