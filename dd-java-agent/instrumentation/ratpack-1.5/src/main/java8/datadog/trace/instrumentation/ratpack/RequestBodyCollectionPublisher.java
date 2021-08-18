package datadog.trace.instrumentation.ratpack;

import datadog.trace.api.http.StoredByteBody;
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
            storedByteBody.maybeNotify(); // TODO: blocking if possible
            if (done.compareAndSet(false, true)) {
              outSubscriber.onError(t);
            }
          }

          @Override
          public void onComplete() {
            storedByteBody.maybeNotify(); // TODO: blocking if possible
            if (done.compareAndSet(false, true)) {
              outSubscriber.onComplete();
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
