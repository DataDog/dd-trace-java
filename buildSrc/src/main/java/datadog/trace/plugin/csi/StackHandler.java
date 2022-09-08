package datadog.trace.plugin.csi;

import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ArgumentSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

/**
 * Implementors of this interface will try to calculate the instructions needed to manipulate the
 * stack in order to convert the current state into the target one.
 *
 * <p>For example, to convert {@code [value3, value2, value1]} into {@code [value3, value1, value3,
 * value2, value1} implementors should return: {@code [DUP2_X1, POP2, DUP_X2, DUP2_X1, POP]}
 *
 * <p>If no combination is possible, implementors should return an empty optional.
 */
public interface StackHandler {

  int[] NO_INSTRUCTIONS = new int[0];
  String DELIMITER = "|";

  @Nonnull
  Optional<int[]> calculateInstructions(@Nonnull StackEntry current, @Nonnull StackEntry target);

  @Nonnull
  Optional<int[]> calculateInstructions(@Nonnull AdviceSpecification advice);

  abstract class AbstractStackHandler implements StackHandler {
    @Nonnull
    @Override
    public Optional<int[]> calculateInstructions(@Nonnull final AdviceSpecification spec) {
      if (spec instanceof AroundSpecification) {
        return Optional.of(NO_INSTRUCTIONS);
      }
      final List<String> advice = new ArrayList<>();
      final List<String> pointcut = new ArrayList<>();
      if (!spec.isStaticPointcut()) {
        Type owner = spec.getPointcut().getOwner();
        appendToStack(pointcut, 0, owner);
        // in constructors this is already in the stack, no need to duplicate it
        if (spec.hasThis() && !spec.isConstructor()) {
          appendToStack(advice, 0, owner);
        }
      }
      final Set<Integer> adviceParameters =
          spec.getArguments().map(ArgumentSpecification::getIndex).collect(Collectors.toSet());
      final Type[] arguments = spec.getPointcut().getMethodType().getArgumentTypes();
      final int offset = spec.isStaticPointcut() ? 0 : 1;
      for (int i = 0; i < arguments.length; i++) {
        Type argument = arguments[i];
        appendToStack(pointcut, i + offset, argument);
        if (adviceParameters.contains(i)) {
          appendToStack(advice, i + offset, argument);
        }
      }
      final StackEntry pointcutStack = StackEntry.fromList(pointcut);
      final StackEntry callSiteStack = StackEntry.fromList(advice);
      final StackEntry targetStack =
          spec instanceof BeforeSpecification
              ? pointcutStack.append(callSiteStack)
              : callSiteStack.append(pointcutStack);
      return calculateInstructions(pointcutStack, targetStack);
    }

    protected static void appendToStack(
        @Nonnull final List<String> builder, final int index, @Nonnull final Type type) {
      if (type.equals(Type.LONG_TYPE) || type.equals(Type.DOUBLE_TYPE)) {
        builder.add(index + "a");
        builder.add(index + "b");
      } else {
        builder.add(Integer.toString(index));
      }
    }

    protected static boolean isCategoryOne(@Nonnull final String value) {
      return value.chars().allMatch(Character::isDigit);
    }

    protected static boolean isCategoryTwo(
        @Nonnull final String value1, @Nonnull final String value2) {
      if (isCategoryOne(value1) || isCategoryOne(value2)) {
        return false;
      }
      final String index1 = value1.substring(0, value1.length() - 1);
      final String index2 = value2.substring(0, value2.length() - 1);
      final char char1 = value1.charAt(value1.length() - 1);
      final char char2 = value2.charAt(value2.length() - 1);
      return index1.equals(index2) && char1 == 'b' && char2 == 'a';
    }
  }

  class StackEntry {

    public static final StackEntry EMPTY = new StackEntry("");

    private final String value;

    private StackEntry(@Nonnull final String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    public StackEntry append(@Nonnull final StackEntry stack) {
      if (this == EMPTY) {
        return stack;
      }
      if (stack == EMPTY) {
        return this;
      }
      return new StackEntry(value + DELIMITER + stack.value);
    }

    public List<String> asList() {
      final String[] values = value.split(Pattern.quote(DELIMITER));
      return Arrays.asList(values);
    }

    public static StackEntry fromList(@Nonnull final List<?> values) {
      if (values.size() == 0) {
        return EMPTY;
      }
      final String stack =
          values.stream().map(Object::toString).collect(Collectors.joining(DELIMITER));
      return new StackEntry(stack);
    }

    public boolean isEmpty() {
      return this == EMPTY || value.length() == 0;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      StackEntry that = (StackEntry) o;
      return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value);
    }

    @Override
    public String toString() {
      return value;
    }
  }
}
