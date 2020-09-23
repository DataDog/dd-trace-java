package datadog.trace.instrumentation.java.completablefuture;

public final class UniCompletionHelper {

  private static final ThreadLocal<ClaimHolder> CLAIM =
      ThreadLocal.withInitial(() -> new ClaimHolder());

  private static final class ClaimHolder {
    private boolean claim = false;
  }

  public static final void setClaim(boolean claim) {
    ClaimHolder holder = CLAIM.get();
    holder.claim = claim;
  }

  public static boolean getAndResetClaim() {
    return getAndRestoreClaim(false);
  }

  public static boolean getAndRestoreClaim(boolean claim) {
    ClaimHolder holder = CLAIM.get();
    boolean current = holder.claim;
    holder.claim = claim;
    return current;
  }
}
