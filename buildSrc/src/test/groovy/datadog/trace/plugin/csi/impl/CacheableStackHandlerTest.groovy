package datadog.trace.plugin.csi.impl

import datadog.trace.plugin.csi.StackHandler
import datadog.trace.plugin.csi.StackHandler.StackEntry
import spock.lang.Specification

import static java.util.Collections.emptyMap
import static org.objectweb.asm.Opcodes.DUP
import static org.objectweb.asm.Opcodes.DUP2
import static org.objectweb.asm.Opcodes.DUP2_X1
import static org.objectweb.asm.Opcodes.DUP2_X2
import static org.objectweb.asm.Opcodes.POP2

class CacheableStackHandlerTest extends Specification {

  def 'test initial values'() {
    setup:
    final currentStack = StackEntry.fromList(current)
    final targetStack = StackEntry.fromList(target)
    final handler = new CacheableStackHandler()
    final int[] expected = instructions?.toArray() as int[]

    when:
    final result = handler.calculateInstructions(currentStack, targetStack)

    then:
    if (instructions == null) {
      !result.present
    } else {
      final ops = result.get()
      ValidationDrivenStackHandler.validate(currentStack, targetStack, ops)
      ops == expected
    }

    where:
    current         | target                         || instructions
    [0]             | [0]                            || []
    [0]             | [0, 0]                         || [DUP]
    [0, 1]          | [0, 1, 0, 1]                   || [DUP2]
    [0, 1, 2]       | [0, 1, 2, 0, 1, 2]             || [DUP, DUP2_X2, POP2, DUP2_X2, DUP2_X1, POP2]
    [0, 1, 2, 3]    | [0, 1, 2, 3, 0, 1, 2, 3]       || [DUP2_X2, POP2, DUP2_X2, DUP2_X2, POP2, DUP2_X2]
    [0, 1, 2, 3, 4] | [0, 1, 2, 3, 4, 0, 1, 2, 3, 4] || null
  }

  def 'test cache update on resolution'() {
    setup:
    final delegated = Mock(StackHandler)
    final handler = new CacheableStackHandler(emptyMap(), delegated)
    final currentStack = StackEntry.fromList([1, 2, 3])
    final targetStack = StackEntry.fromList([1, 2, 3, 1, 2, 3])
    final result = new int[]{
      1, 2, 3
    }

    when:
    final result1 = handler.calculateInstructions(currentStack, targetStack)
    final result2 = handler.calculateInstructions(currentStack, targetStack)

    then:
    1 * delegated.calculateInstructions(currentStack, targetStack) >> Optional.of(result)
    result1.get() == result
    result2.get() == result
  }

  def 'test cache does not query twice for the same value'() {
    setup:
    final delegated = Mock(StackHandler)
    final handler = new CacheableStackHandler(emptyMap(), delegated)
    final currentStack = StackEntry.fromList([0, 1])
    final targetStack = StackEntry.fromList([0, 1, 0, 1])

    when:
    final result1 = handler.calculateInstructions(currentStack, targetStack)
    final result2 = handler.calculateInstructions(currentStack, targetStack)

    then:
    1 * delegated.calculateInstructions(currentStack, targetStack) >> Optional.empty()
    !result1.present
    !result2.present
  }
}
