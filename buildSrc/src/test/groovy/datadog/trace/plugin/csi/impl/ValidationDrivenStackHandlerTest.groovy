package datadog.trace.plugin.csi.impl


import datadog.trace.plugin.csi.StackHandler
import spock.lang.Specification

import static org.objectweb.asm.Opcodes.DUP
import static org.objectweb.asm.Opcodes.DUP2
import static org.objectweb.asm.Opcodes.DUP2_X1
import static org.objectweb.asm.Opcodes.DUP2_X2
import static org.objectweb.asm.Opcodes.DUP_X1
import static org.objectweb.asm.Opcodes.DUP_X2
import static org.objectweb.asm.Opcodes.POP
import static org.objectweb.asm.Opcodes.POP2
import static org.objectweb.asm.Opcodes.SWAP

class ValidationDrivenStackHandlerTest extends Specification {

  def 'test validation based stack handler'() {
    setup:
    final currentStack = StackHandler.StackEntry.fromList(current)
    final targetStack = StackHandler.StackEntry.fromList(target)
    final handler = new ValidationDrivenStackHandler()
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
    [0, 1]          | [0, 1, 1]                      || [DUP]
    [0, 1]          | [0, 1, 0]                      || [DUP2, POP]
    [0, 1]          | [1, 0, 1]                      || [DUP_X1]
    [0, 1]          | [1, 1, 0]                      || [DUP_X1, SWAP]
    [0, 1, 2]       | [0, 0, 1, 2]                   || [DUP2_X1, POP2, DUP, DUP2_X2, POP2]
    [0, 1, 2]       | [1, 0, 1, 2]                   || [SWAP, DUP_X2, SWAP]
    [0, 1, 2]       | [2, 0, 1, 2]                   || [DUP_X2]
    [0, 1, 2]       | [0, 1, 0, 1, 2]                || [DUP_X2, POP, DUP2_X1, DUP2_X1, POP2]
    [0, 1, 2]       | [0, 2, 0, 1, 2]                || [DUP2_X1, POP2, DUP_X2, DUP2_X1, POP]
    [0, 1, 2]       | [1, 2, 0, 1, 2]                || [DUP2_X1]
    [0, 1, 2, 3]    | [0, 1, 2, 3, 0, 1, 2, 3]       || [DUP2_X2, POP2, DUP2_X2, DUP2_X2, POP2, DUP2_X2]
    [0, 1, 2, 3, 4] | [0, 1, 2, 3, 4, 0, 1, 2, 3, 4] || null
  }
}

