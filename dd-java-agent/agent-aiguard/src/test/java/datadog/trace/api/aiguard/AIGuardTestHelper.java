package datadog.trace.api.aiguard;

public class AIGuardTestHelper {

  private AIGuardTestHelper() {}

  /** Reads the evaluator installed in {@link AIGuard}, which is not visible outside its package. */
  public static Evaluator installedEvaluator() {
    return AIGuard.EVALUATOR;
  }
}
