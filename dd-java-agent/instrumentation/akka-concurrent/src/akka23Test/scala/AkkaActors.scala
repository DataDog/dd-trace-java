import java.util.concurrent.Semaphore

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.{
  activateSpan,
  activeScope,
  activeSpan,
  startSpan
}

import scala.concurrent.duration._

class AkkaActors extends AutoCloseable {
  val system: ActorSystem = ActorSystem("akka-actors-test")
  val receiver: ActorRef =
    system.actorOf(Receiver.props, "receiver")
  val forwarder: ActorRef =
    system.actorOf(Forwarder.props(receiver), "forwarder")
  val tellGreeter: ActorRef =
    system.actorOf(Greeter.props("Howdy", receiver), "tell-greeter")
  val askGreeter: ActorRef =
    system.actorOf(Greeter.props("Hi-diddly-ho", receiver), "ask-greeter")
  val forwardGreeter: ActorRef =
    system.actorOf(Greeter.props("Hello", forwarder), "forward-greeter")

  override def close(): Unit = {
    system.terminate()
  }

  import Greeter._

  implicit val timeout: Timeout = 10.seconds

  private val actors =
    Map("tell" -> tellGreeter, "ask" -> askGreeter, "forward" -> forwardGreeter)

  def block(name: String): Semaphore = {
    val barrier = new Semaphore(0)
    actors(name) ! Block(barrier)
    barrier
  }

  @Trace
  def send(name: String, who: String): Unit = {
    val actor = actors(name)
    activeScope().setAsyncPropagation(true)
    activeSpan().setSpanName(name)
    actor ! WhoToGreet(who)
    if (name == "ask") {
      actor ? Greet
    } else {
      actor ! Greet
    }
  }

  @Trace
  def leak(who: String, leak: String): Unit = {
    activeScope().setAsyncPropagation(true)
    activeSpan().setSpanName("leak all the things")
    tellGreeter ! WhoToGreet(who)
    tellGreeter ! Leak(leak)
  }
}

object Greeter {
  def props(message: String, receiverActor: ActorRef): Props =
    Props(new Greeter(message, receiverActor))

  final case class Block(barrier: Semaphore)
  final case class WhoToGreet(who: String)
  case object Greet
  final case class Leak(leak: String)
}

class Greeter(message: String, receiverActor: ActorRef) extends Actor {
  import Greeter._
  import Receiver._

  var greeting = ""

  def receive = {
    case Block(barrier) =>
      barrier.acquire()
    case WhoToGreet(who) =>
      greeting = s"$message, $who"
    case Greet =>
      receiverActor ! Greeting(greeting)
    case Leak(leak) =>
      val span = startSpan(greeting)
      span.setResourceName(leak)
      activateSpan(span)
      span.finish()
  }
}

object Receiver {
  def props: Props = Props[Receiver]

  final case class Greeting(greeting: String)
}

class Receiver extends Actor with ActorLogging {

  import Receiver._

  def receive = {
    case Greeting(greeting) => {
      tracedChild(greeting)
    }
  }

  @Trace
  def tracedChild(opName: String): Unit = {
    activeSpan().setSpanName(opName)
  }
}

object Forwarder {
  def props(receiverActor: ActorRef): Props =
    Props(new Forwarder(receiverActor))
}

class Forwarder(receiverActor: ActorRef) extends Actor with ActorLogging {
  def receive = {
    case msg => {
      receiverActor forward msg
    }
  }
}
