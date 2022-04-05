package datadog.trace.instrumentation.aws.v1.lambda;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.asm.Advice.AllArguments;
import static net.bytebuddy.asm.Advice.Enter;
import static net.bytebuddy.asm.Advice.OnMethodEnter;
import static net.bytebuddy.asm.Advice.OnMethodExit;
import static net.bytebuddy.asm.Advice.Origin;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class LambdaHandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  private String instrumentedType;
  private String methodName;

  public LambdaHandlerInstrumentation() {
    super("aws-lambda");
    final String handler = System.getenv("_HANDLER");
    if (null != handler) {
      final String[] tokens = handler.split("::");
      if (tokens.length == 1) {
        this.instrumentedType = handler;
        this.methodName = "handleRequest";
      } else if (tokens.length == 2) {
        this.instrumentedType = tokens[0];
        this.methodName = tokens[1];
      }
      System.out.println("instrumentedType=" + this.instrumentedType);
      System.out.println("methodName=" + this.methodName);
    }
  }

  @Override
  protected boolean defaultEnabled() {
    return true;
  }

  @Override
  public String instrumentedType() {
    return this.instrumentedType;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    if (null != this.instrumentedType && null != this.methodName) {
      transformation.applyAdvice(
          isMethod().and(named(this.methodName)),
          getClass().getName() + "$ExtensionCommunicationAdvice");
    }
  }

  public static class ExtensionCommunicationAdvice {

//    static void post(String url) {
//      URL urlToOpen = new URL(url);
//      URLConnection con = urlToOpen.openConnection();
//      con.setDoOutput(true);
//      HttpURLConnection http = (HttpURLConnection) con;
//      http.setRequestMethod("POST");
//
//      byte[] out =
//          "{\"username\":\"root\",\"password\":\"password\"}".getBytes(StandardCharsets.UTF_8);
//      int length = out.length;
//      http.setFixedLengthStreamingMode(length);
//      http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
//      http.connect();
//      try (OutputStream os = http.getOutputStream()) {
//        os.write(out);
//      }
//    }

    @OnMethodEnter
    static long enter() {
      System.out.println("[maxday-poc-java-no-code] - Enter the function");
//      try {
//        post("\"http://127.0.0.1:8124/lambda/start-invocation");
//      } catch (Exception e){
//        System.out.println("ooopsy = " + e);
//      }
      return System.currentTimeMillis();
    }

    @OnMethodExit
    static void exit(@Origin String method, @Enter long start, @AllArguments Object[] args) {
      System.out.println("[maxday-poc-java-no-code] - Exit the function" + method);
//      try {
//        post("\"http://127.0.0.1:8124/lambda/end-invocation");
//      } catch (Exception e){
//        System.out.println("ooopsy = " + e);
//      }
      System.out.println("[maxday-poc-java-no-code] - Took " + (System.currentTimeMillis() - start) + " milliseconds ");
    }
  }
}
