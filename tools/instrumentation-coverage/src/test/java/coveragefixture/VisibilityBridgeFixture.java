package coveragefixture;

/** Public subclasses of hidden parents can expose compiler-generated visibility bridges. */
public class VisibilityBridgeFixture extends HiddenParent {
  public static Class<?> parentType() {
    return HiddenParent.class;
  }
}

class HiddenParent {
  public boolean inherited() {
    return true;
  }
}
