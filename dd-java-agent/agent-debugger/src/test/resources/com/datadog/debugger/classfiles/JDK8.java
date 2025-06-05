import java.util.Arrays;

public class JDK8 implements Runnable {
    private static final String JDK8 = "JDK8";
    private static final int INT_CST = 42;
    private static final long LONG_CST = 100_000_000_000L;
    private static final double DOUBLE_CST = 3.14;
    private static final float FLOAT_CST = 2.68F;

    public static void main(String[] args) {
        try {
            System.out.println("Hello, JDK8!");
            Runnable runnable = new JDK8();
            runnable.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        System.out.printf("running %n");
        Arrays.asList("1", "2").stream().forEach(System.out::println);
    }
}
