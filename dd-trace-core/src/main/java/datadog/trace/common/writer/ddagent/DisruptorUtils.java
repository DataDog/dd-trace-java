package datadog.trace.common.writer.ddagent;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import java.util.concurrent.ThreadFactory;

public final class DisruptorUtils {

  public static <T> Disruptor<T> create(
      EventFactory<T> factory,
      int ringBufferSize,
      ThreadFactory threadFactory,
      ProducerType producerType,
      WaitStrategy waitStrategy) {
    return new Disruptor<>(
        factory, roundUpToPowerOfTwo(ringBufferSize), threadFactory, producerType, waitStrategy);
  }

  private static int roundUpToPowerOfTwo(int size) {
    return 1 << -Integer.numberOfLeadingZeros(size - 1);
  }
}
