package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.util.EnumSet;
import net.bytebuddy.description.ModifierReviewable;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;

public class ModifierMatchers {

  public enum ModifierConstraint {
    PUBLIC(false, Opcodes.ACC_PUBLIC),
    NON_PUBLIC(true, Opcodes.ACC_PUBLIC),
    PROTECTED(false, Opcodes.ACC_PROTECTED),
    NON_PROTECTED(true, Opcodes.ACC_PROTECTED),
    PRIVATE(false, Opcodes.ACC_PRIVATE),
    NON_PRIVATE(true, Opcodes.ACC_PRIVATE),
    STATIC(false, Opcodes.ACC_STATIC),
    NON_STATIC(true, Opcodes.ACC_STATIC),
    ABSTRACT(false, Opcodes.ACC_ABSTRACT),
    NON_ABSTRACT(true, Opcodes.ACC_ABSTRACT),
    FINAL(false, Opcodes.ACC_FINAL),
    NON_FINAL(true, Opcodes.ACC_FINAL),
    ;

    private final boolean forbidden;
    private final int modifier;

    ModifierConstraint(boolean forbidden, int modifier) {
      this.forbidden = forbidden;
      this.modifier = modifier;
    }

    static long toMasks(EnumSet<ModifierConstraint> constraints) {
      long masks = 0L;
      for (ModifierConstraint constraint : constraints) {
        masks |= (((long) constraint.modifier) << (constraint.forbidden ? 32 : 0));
      }
      return masks;
    }
  }

  /**
   * Checks that an element satisfies at least one of the positive modifier constraints and violates
   * none of the negative modifier constraints.
   *
   * @param constraints conditions element modifiers must satisfy
   * @param <T>
   * @return a matcher
   */
  public static <T extends ModifierReviewable>
      ElementMatcher.Junction<T> anyPermittedNoForbiddenModifiers(
          EnumSet<ModifierConstraint> constraints) {
    long masks = ModifierConstraint.toMasks(constraints);
    return new AllowPermittedDenyForbidden<>((int) masks, (int) (masks >>> 32));
  }

  private static class AllowPermittedDenyForbidden<T extends ModifierReviewable>
      extends ElementMatcher.Junction.AbstractBase<T> {

    private final int permitted;
    private final int forbidden;

    private AllowPermittedDenyForbidden(int permitted, int forbidden) {
      assert (permitted & forbidden) == 0 : "cannot permit and forbid the same modifier";
      assert permitted != 0 : "at least one modifier should be allowed";
      this.permitted = permitted;
      this.forbidden = forbidden;
    }

    @Override
    public boolean matches(T target) {
      int modifiers = target.getModifiers();
      return ((modifiers & permitted) != 0) & ((modifiers & forbidden) == 0);
    }
  }
}
