package com.datadog.openfeature.internal.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/** The request body publisher of a chunked post, against the Flow contract. */
class ChunksPublisherTest {
  private static final List<ByteBuffer> CHUNKS =
      Arrays.asList(chunk("ab"), chunk("cd"), chunk("ef"));

  @Test
  void publishesTheChunksInOrderAsRequested() {
    final Recorder recorder = subscribe(CHUNKS);

    recorder.subscription.request(1);
    assertEquals(Collections.singletonList("ab"), recorder.received);
    assertEquals(0, recorder.completions);

    recorder.subscription.request(5);
    assertEquals(Arrays.asList("ab", "cd", "ef"), recorder.received);
    assertEquals(1, recorder.completions);

    recorder.subscription.request(1); // after completion: ignored
    assertEquals(3, recorder.received.size());
    assertEquals(1, recorder.completions);
  }

  @Test
  void completesAnEmptyBodyOnTheFirstRequest() {
    final Recorder recorder = subscribe(Collections.emptyList());

    recorder.subscription.request(1);

    assertTrue(recorder.received.isEmpty());
    assertEquals(1, recorder.completions);
  }

  @Test
  void publishesReadOnlyViewsAndLeavesTheChunksUnread() {
    final ByteBuffer original = chunk("ab");
    final Recorder recorder = subscribe(Collections.singletonList(original));

    recorder.subscription.request(1);

    assertThrows(ReadOnlyBufferException.class, () -> recorder.buffers.get(0).put((byte) 'x'));
    assertEquals(2, original.remaining());
  }

  @Test
  void signalsAnErrorForANonPositiveRequest() {
    final Recorder recorder = subscribe(CHUNKS);

    recorder.subscription.request(0);
    recorder.subscription.request(1); // after the error: ignored

    assertInstanceOf(IllegalArgumentException.class, recorder.error);
    assertTrue(recorder.received.isEmpty());
    assertEquals(0, recorder.completions);
  }

  @Test
  void stopsAfterCancel() {
    final Recorder recorder = subscribe(CHUNKS);

    recorder.subscription.request(1);
    recorder.subscription.cancel();
    recorder.subscription.request(5);

    assertEquals(Collections.singletonList("ab"), recorder.received);
    assertEquals(0, recorder.completions);
  }

  @Test
  void stopsWhenTheSubscriberCancelsFromOnNext() {
    final Recorder recorder =
        new Recorder() {
          @Override
          public void onNext(final ByteBuffer item) {
            super.onNext(item);
            subscription.cancel();
          }
        };
    new DirectIntakeTransport.ChunksPublisher(CHUNKS).subscribe(recorder);

    recorder.subscription.request(5);

    assertEquals(Collections.singletonList("ab"), recorder.received);
    assertEquals(0, recorder.completions);
  }

  @Test
  void stopsWhenTheSubscriberCancelsFromTheLastOnNext() {
    final Recorder recorder =
        new Recorder() {
          @Override
          public void onNext(final ByteBuffer item) {
            super.onNext(item);
            if (received.size() == CHUNKS.size()) {
              subscription.cancel();
            }
          }
        };
    new DirectIntakeTransport.ChunksPublisher(CHUNKS).subscribe(recorder);

    recorder.subscription.request(5);

    assertEquals(3, recorder.received.size());
    assertEquals(0, recorder.completions);
  }

  private static Recorder subscribe(final List<ByteBuffer> chunks) {
    final Recorder recorder = new Recorder();
    new DirectIntakeTransport.ChunksPublisher(chunks).subscribe(recorder);
    return recorder;
  }

  private static ByteBuffer chunk(final String text) {
    return ByteBuffer.wrap(text.getBytes(UTF_8));
  }

  private static class Recorder implements Flow.Subscriber<ByteBuffer> {
    Flow.Subscription subscription;
    final List<String> received = new ArrayList<>();
    final List<ByteBuffer> buffers = new ArrayList<>();
    int completions;
    Throwable error;

    @Override
    public void onSubscribe(final Flow.Subscription subscription) {
      this.subscription = subscription;
    }

    @Override
    public void onNext(final ByteBuffer item) {
      buffers.add(item);
      final byte[] bytes = new byte[item.remaining()];
      item.duplicate().get(bytes);
      received.add(new String(bytes, UTF_8));
    }

    @Override
    public void onError(final Throwable throwable) {
      error = throwable;
    }

    @Override
    public void onComplete() {
      completions++;
    }
  }
}
