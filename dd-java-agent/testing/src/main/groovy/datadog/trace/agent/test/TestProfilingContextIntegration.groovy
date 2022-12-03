package datadog.trace.agent.test

import com.google.common.collect.Sets
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TestProfilingContextIntegration implements ProfilingContextIntegration {
  final AtomicInteger attachments = new AtomicInteger()
  final AtomicInteger detachments = new AtomicInteger()
  final Map<Integer, Integer> poolParallelism = new ConcurrentHashMap<>()
  final Set<Integer> clearedPoolParallelism = Sets.newConcurrentHashSet()
  @Override
  void onAttach(int tid) {
    attachments.incrementAndGet()
  }

  @Override
  void onDetach(int tid) {
    detachments.incrementAndGet()
  }

  @Override
  void setContext(int tid, long rootSpanId, long spanId) {
  }

  @Override
  void setPoolParallelism(int parallelism) {
    poolParallelism.put(getNativeThreadId(), parallelism)
  }

  @Override
  void clearPoolParallelism() {
    clearedPoolParallelism.add(getNativeThreadId())
  }

  @Override
  int getNativeThreadId() {
    return (int) Thread.currentThread().id
  }

  void clear() {
    attachments.set(0)
    detachments.set(0)
    poolParallelism.clear()
    clearedPoolParallelism.clear()
  }
}
