package com.datadog.iast.util;

import com.datadog.iast.sensitive.SensitiveHandler.Tokenizer;
import java.util.Deque;
import java.util.LinkedList;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** */
public interface RangedDeque<E extends Ranged> {

  @Nullable
  E poll();

  @Nullable
  E peek();

  void addFirst(@Nonnull E item);

  boolean isEmpty();

  static RangedDeque<Ranged> forTokenizer(@Nonnull final Tokenizer tokenizer) {
    return new TokenizerQueue(tokenizer);
  }

  static <E extends Ranged> RangedDeque<E> forArray(@Nullable final E[] array) {
    return array == null || array.length == 0
        ? new EmptyRangedDequeue<>()
        : new ArrayQueue<>(array);
  }

  abstract class BaseRangedDequeue<E extends Ranged> implements RangedDeque<E> {

    private final Deque<E> head = new LinkedList<>();

    @Nullable protected E next;

    @Nullable
    @Override
    public final E poll() {
      final E result = next;
      next = fetchNext();
      return result;
    }

    @Nullable
    @Override
    public final E peek() {
      return next;
    }

    @Override
    public final void addFirst(@Nonnull final E item) {
      if (next != null) {
        head.addFirst(next);
      }
      next = item;
    }

    @Override
    public final boolean isEmpty() {
      return next == null;
    }

    @Nullable
    protected final E fetchNext() {
      return head.isEmpty() ? internalPoll() : head.poll();
    }

    @Nullable
    protected abstract E internalPoll();
  }

  class EmptyRangedDequeue<E extends Ranged> extends BaseRangedDequeue<E> {

    @Nullable
    @Override
    protected E internalPoll() {
      return null;
    }
  }

  class TokenizerQueue extends BaseRangedDequeue<Ranged> {

    private final Tokenizer tokenizer;

    TokenizerQueue(final Tokenizer tokenizer) {
      this.tokenizer = tokenizer;
      next = fetchNext();
    }

    @Nullable
    @Override
    protected Ranged internalPoll() {
      return tokenizer.next() ? tokenizer.current() : null;
    }
  }

  class ArrayQueue<E extends Ranged> extends BaseRangedDequeue<E> {

    private final E[] array;
    private int index;

    ArrayQueue(final E[] array) {
      this.array = array;
      index = 0;
      next = fetchNext();
    }

    @Nullable
    @Override
    protected E internalPoll() {
      return index >= array.length ? null : array[index++];
    }
  }
}
