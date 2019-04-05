import java.io.IOException;

public class DoNothing {
	public static void main(String[] args) throws IOException {
		System.out.println("Press <enter> to quit!");
		System.in.read();
	}
}
