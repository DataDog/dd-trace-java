package datadog.trace.plugin.csi.impl;

import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.DUP2;
import static org.objectweb.asm.Opcodes.DUP2_X1;
import static org.objectweb.asm.Opcodes.DUP2_X2;
import static org.objectweb.asm.Opcodes.DUP_X1;
import static org.objectweb.asm.Opcodes.DUP_X2;
import static org.objectweb.asm.Opcodes.NOP;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.POP2;
import static org.objectweb.asm.Opcodes.SWAP;

import datadog.trace.plugin.csi.StackHandler;
import datadog.trace.plugin.csi.StackHandler.AbstractStackHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

/**
 * Implementation of {@link StackHandler} that uses an in-memory representation of the stack and
 * tries combinations of stack manipulation instructions (DUP, POP, SWAP, ...) to archive the
 * desired outcome.
 */
public class ValidationDrivenStackHandler extends AbstractStackHandler {

  private static final int MAX_VALIDATE_DEPTH = 6;
  private static final Map<Integer, InstructionHandler> HANDLERS = new LinkedHashMap<>();

  static {
    HANDLERS.put(
        DUP,
        new InstructionHandler(
            DUP_X1,
            list -> {
              final String value1 = list.get(list.size() - 1);
              return isCategoryOne(value1);
            },
            list -> {
              final String value1 = list.get(list.size() - 1);
              list.add(value1);
            }));
    HANDLERS.put(
        DUP_X1,
        new InstructionHandler(
            DUP_X2,
            list -> {
              final String value1 = list.get(list.size() - 1);
              final String value2 = list.get(list.size() - 2);
              return isCategoryOne(value1) && isCategoryOne(value2);
            },
            list -> {
              final String value1 = list.get(list.size() - 1);
              list.add(list.size() - 2, value1);
            }));
    HANDLERS.put(
        DUP_X2,
        new InstructionHandler(
            DUP2,
            list -> {
              final String value1 = list.get(list.size() - 1);
              final String value2 = list.get(list.size() - 2);
              final String value3 = list.get(list.size() - 3);
              if (isCategoryOne(value1) && isCategoryOne(value2) && isCategoryOne(value3)) {
                return true;
              }
              return isCategoryOne(value1) && isCategoryTwo(value2, value3);
            },
            list -> {
              final String value1 = list.get(list.size() - 1);
              list.add(list.size() - 3, value1);
            }));
    HANDLERS.put(
        DUP2,
        new InstructionHandler(
            DUP2_X1,
            list -> {
              final String value1 = list.get(list.size() - 1);
              final String value2 = list.get(list.size() - 2);
              if (isCategoryOne(value1) && isCategoryOne(value2)) {
                return true;
              }
              return isCategoryTwo(value1, value2);
            },
            list -> {
              final String value1 = list.get(list.size() - 1);
              final String value2 = list.get(list.size() - 2);
              list.add(list.size() - 2, value2);
              list.add(list.size() - 2, value1);
            }));
    HANDLERS.put(
        DUP2_X1,
        new InstructionHandler(
            DUP2_X2,
            list -> {
              final String value1 = list.get(list.size() - 1);
              final String value2 = list.get(list.size() - 2);
              final String value3 = list.get(list.size() - 3);
              if (isCategoryOne(value1) && isCategoryOne(value2) && isCategoryOne(value3)) {
                return true;
              }
              return isCategoryTwo(value1, value2) && isCategoryOne(value3);
            },
            list -> {
              final String value1 = list.get(list.size() - 1);
              final String value2 = list.get(list.size() - 2);
              list.add(list.size() - 3, value2);
              list.add(list.size() - 3, value1);
            }));
    HANDLERS.put(
        DUP2_X2,
        new InstructionHandler(
            POP,
            list -> {
              final String value1 = list.get(list.size() - 1);
              final String value2 = list.get(list.size() - 2);
              final String value3 = list.get(list.size() - 3);
              final String value4 = list.get(list.size() - 4);
              if (isCategoryOne(value1)
                  && isCategoryOne(value2)
                  && isCategoryOne(value3)
                  && isCategoryOne(value4)) {
                return true;
              }
              if (isCategoryTwo(value1, value2) && isCategoryOne(value3) && isCategoryOne(value4)) {
                return true;
              }
              if (isCategoryOne(value1) && isCategoryOne(value2) && isCategoryTwo(value3, value4)) {
                return true;
              }
              return isCategoryTwo(value1, value2) && isCategoryTwo(value3, value4);
            },
            list -> {
              final String value1 = list.get(list.size() - 1);
              final String value2 = list.get(list.size() - 2);
              list.add(list.size() - 4, value2);
              list.add(list.size() - 4, value1);
            }));
    HANDLERS.put(
        POP,
        new InstructionHandler(
            POP2,
            list -> {
              final String value1 = list.get(list.size() - 1);
              return isCategoryOne(value1);
            },
            list -> {
              list.remove(list.size() - 1);
            }));
    HANDLERS.put(
        POP2,
        new InstructionHandler(
            SWAP,
            list -> {
              final String value1 = list.get(list.size() - 1);
              final String value2 = list.get(list.size() - 2);
              if (isCategoryOne(value1) && isCategoryOne(value2)) {
                return true;
              }
              return isCategoryTwo(value1, value2);
            },
            list -> {
              list.remove(list.size() - 1);
              list.remove(list.size() - 1);
            }));
    HANDLERS.put(
        SWAP,
        new InstructionHandler(
            NOP,
            list -> {
              final String value1 = list.get(list.size() - 1);
              final String value2 = list.get(list.size() - 2);
              return isCategoryOne(value1) && isCategoryOne(value2);
            },
            list -> {
              final String value1 = list.remove(list.size() - 1);
              list.add(list.size() - 1, value1);
            }));
    HANDLERS.put(NOP, new InstructionHandler());
  }

  private final int maxDepth;

  public ValidationDrivenStackHandler() {
    this(MAX_VALIDATE_DEPTH);
  }

  public ValidationDrivenStackHandler(final int maxDepth) {
    this.maxDepth = maxDepth;
  }

  @Override
  @Nonnull
  public Optional<int[]> calculateInstructions(
      @Nonnull final StackEntry current, @Nonnull final StackEntry target) {
    if (current.equals(target)) {
      return Optional.of(NO_INSTRUCTIONS);
    }

    final List<String> currentList = current.asList();
    final List<String> targetList = target.asList();
    for (int i = 1; i < maxDepth + 1; i++) {
      final int[] instructions = new int[i];
      Arrays.fill(instructions, DUP);
      boolean completed = false;
      while (!completed) {
        if (validate(currentList, targetList, instructions)) {
          return Optional.of(instructions);
        }
        for (int z = instructions.length - 1; z >= 0; z--) {
          if (instructions[z] != NOP) {
            instructions[z] = handlerFor(instructions[z]).getNext();
            if (z != instructions.length - 1) {
              Arrays.fill(instructions, z + 1, instructions.length, DUP);
            }
            break;
          }
          if (z == 0) {
            completed = true;
          }
        }
      }
    }
    return Optional.empty();
  }

  public static boolean validate(
      @Nonnull final StackEntry current,
      @Nonnull final StackEntry target,
      @Nonnull final int[] instructions) {
    return validate(current.asList(), target.asList(), instructions);
  }

  public static boolean validate(
      @Nonnull final List<String> current,
      @Nonnull final List<String> target,
      @Nonnull final int[] instructions) {
    try {
      List<String> result = new ArrayList<>(current);
      Arrays.stream(instructions)
          .mapToObj(ValidationDrivenStackHandler::handlerFor)
          .forEach(it -> it.accept(result));
      if (result.size() != target.size()) {
        return false;
      }
      for (int i = 0; i < result.size(); i++) {
        if (!result.get(i).equals(target.get(i))) {
          return false;
        }
      }
      return true;
    } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
      return false; // not valid instruction list
    }
  }

  private static InstructionHandler handlerFor(final int instruction) {
    return HANDLERS.get(instruction);
  }

  private static final class InstructionHandler implements Consumer<List<String>> {
    private final int next;

    private final Predicate<List<String>> predicate;

    private final Consumer<List<String>> handler;

    public InstructionHandler() {
      this(-1, null, null);
    }

    public InstructionHandler(
        final int next,
        final Predicate<List<String>> predicate,
        final Consumer<List<String>> handler) {
      this.next = next;
      this.predicate = predicate;
      this.handler = handler;
    }

    @Override
    public void accept(final List<String> list) {
      if (predicate == null || handler == null || list == null) {
        return;
      }
      if (!predicate.test(list)) {
        throw new IllegalArgumentException("Instruction not applicable to stack");
      }
      handler.accept(list);
    }

    public int getNext() {
      return next;
    }
  }
}
