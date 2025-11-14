package datadog.trace.instrumentation.aws.v2.sqs;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import software.amazon.awssdk.services.sqs.model.Message;

public class TracingList implements List<Message> {
  private final ContextStore<Message, State> messageStateStore;
  private final List<Message> delegate;
  private final String queueUrl;
  private final String requestId;

  public TracingList(
      ContextStore<Message, State> messageStateStore,
      List<Message> delegate,
      String queueUrl,
      String requestId) {
    this.messageStateStore = messageStateStore;
    this.delegate = delegate;
    this.queueUrl = queueUrl;
    this.requestId = requestId;
  }

  @Override
  public int size() {
    return delegate.size();
  }

  @Override
  public boolean isEmpty() {
    return delegate.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    return delegate.contains(o);
  }

  @Override
  public Iterator<Message> iterator() {
    return listIterator(0);
  }

  @Override
  public Object[] toArray() {
    return delegate.toArray();
  }

  @Override
  public <T> T[] toArray(T[] a) {
    return delegate.toArray(a);
  }

  @Override
  public boolean add(Message message) {
    return delegate.add(message);
  }

  @Override
  public boolean remove(Object o) {
    return delegate.remove(o);
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    return delegate.containsAll(c);
  }

  @Override
  public boolean addAll(Collection<? extends Message> c) {
    return delegate.addAll(c);
  }

  @Override
  public boolean addAll(int index, Collection<? extends Message> c) {
    return delegate.addAll(index, c);
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    return delegate.removeAll(c);
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    return delegate.retainAll(c);
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  @Override
  public Message get(int index) {
    return delegate.get(index); // not currently covered by iteration span
  }

  @Override
  public Message set(int index, Message element) {
    return delegate.set(index, element);
  }

  @Override
  public void add(int index, Message element) {
    delegate.add(index, element);
  }

  @Override
  public Message remove(int index) {
    return delegate.remove(index);
  }

  @Override
  public int indexOf(Object o) {
    return delegate.indexOf(o);
  }

  @Override
  public int lastIndexOf(Object o) {
    return delegate.lastIndexOf(o);
  }

  @Override
  public ListIterator<Message> listIterator() {
    return listIterator(0);
  }

  @Override
  public ListIterator<Message> listIterator(int index) {
    // every iteration will add spans. Not only the very first one
    return new TracingListIterator(
        messageStateStore, delegate.listIterator(index), queueUrl, requestId);
  }

  @Override
  public List<Message> subList(int fromIndex, int toIndex) {
    return new TracingList(
        messageStateStore, delegate.subList(fromIndex, toIndex), queueUrl, requestId);
  }

  @Override
  public String toString() {
    return delegate.toString();
  }
}
