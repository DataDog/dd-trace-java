package datadog.common.queue;

public final class MpscBlockingConsumerVarHandleArrayQueue<E>
    extends org.jctools.queues.varhandle.MpscBlockingConsumerVarHandleArrayQueue<E>
    implements MessagePassingBlockingQueue<E> {

  public MpscBlockingConsumerVarHandleArrayQueue(int capacity) {
    super(capacity);
  }
}
