import java.util.function.Supplier;

public class OuterClass {

  public final long anonymous =
      new Supplier<Long>() {
        @Override
        public Long get() {
          return 1L;
        }
      }.get();

  public final InnerClass inner = new InnerClass();

  public final InnerStaticClass innerStatic = new InnerStaticClass();

  public class InnerClass {}

  public static class InnerStaticClass {}
}
