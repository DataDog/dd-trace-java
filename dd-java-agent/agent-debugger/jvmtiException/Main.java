public class Main {
    private int intField = 21;
    private String strField = "foobar";
    public static void main(String[] args) throws Exception {
        int i = 42;
        String s = "coucou";
        System.out.println(s);
        if (i > 0) {
            //throw new RuntimeException("oops");
            new Main().process(s);
        }
    }

    private void process(String arg) {
        throw new RuntimeException("oops I did it again");
    }

}
