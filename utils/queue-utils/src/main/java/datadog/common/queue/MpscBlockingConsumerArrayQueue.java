package datadog.common.queue;

public final class MpscBlockingConsumerArrayQueue<E>
    extends org.jctools.queues.MpscBlockingConsumerArrayQueue<E>
    implements MessagePassingBlockingQueue<E> {

  public MpscBlockingConsumerArrayQueue(int capacity) {
    super(capacity);
  }
}
