package datadog.trace.instrumentation.http4s021_212;

import cats.effect.Resource;
import net.bytebuddy.asm.Advice;
import org.http4s.Uri;
import org.http4s.client.Client;
import org.http4s.client.blaze.BlazeClientBuilder;
import scala.concurrent.impl.CallbackRunnable;

public class Http4sClientBuilderAdvice {
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static <F> void exit(
      @Advice.This BlazeClientBuilder<F> zis,
      @Advice.Return(readOnly = false) Resource<F, Client<F>> retVal) {
    retVal = ClientWrapper$.MODULE$.resource(retVal, zis.F());
  }

  /**
   * CallbackRunnable was removed in scala 2.13<br>
   * Uri.Scheme.fromString method was introduced in http4s 0.21.0
   */
  private static void muzzleCheck(final CallbackRunnable<?> callback) {
    callback.run();
    Uri.Scheme$.MODULE$.fromString("unused");
  }
}
