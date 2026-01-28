package datadog.common.queue;

import java.util.concurrent.BlockingQueue;
import org.jctools.queues.MessagePassingQueue;

public interface MessagePassingBlockingQueue<E> extends BlockingQueue<E>, MessagePassingQueue<E> {}
