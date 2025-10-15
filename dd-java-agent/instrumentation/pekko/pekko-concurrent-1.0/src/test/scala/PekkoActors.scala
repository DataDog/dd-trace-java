import Scheduler.Schedule

import java.util.concurrent.Semaphore
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import org.apache.pekko.pattern.ask
import org.apache.pekko.routing.RoundRobinPool
import org.apache.pekko.util.Timeout
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.{
  activateSpan,
  activeSpan,
  setAsyncPropagationEnabled,
  startSpan
}

import scala.concurrent.duration._

class PekkoActors extends AutoCloseable {
  val system: ActorSystem = ActorSystem("pekko-actors-test")
  val receiver: ActorRef =
    system.actorOf(Receiver.props, "receiver")
  val forwarder: ActorRef =
    system.actorOf(Forwarder.props(receiver), "forwarder")
  val router: ActorRef =
    system.actorOf(RoundRobinPool(5).props(Props[Receiver]()), "router")
  val scheduler: ActorRef =
    system.actorOf(Scheduler.props, "scheduler")

  val tellGreeter: ActorRef =
    system.actorOf(Greeter.props("Howdy", receiver), "tell-greeter")
  val askGreeter: ActorRef =
    system.actorOf(Greeter.props("Hi-diddly-ho", receiver), "ask-greeter")
  val forwardGreeter: ActorRef =
    system.actorOf(Greeter.props("Hello", forwarder), "forward-greeter")
  val routerGreeter: ActorRef =
    system.actorOf(Greeter.props("How you doin'", router), "router-greeter")

  override def close(): Unit = {
    PekkoActors.terminate(system)
  }

  import Greeter._

  implicit val timeout: Timeout = 10.seconds

  private val actors =
    Map(
      "tell"    -> tellGreeter,
      "ask"     -> askGreeter,
      "forward" -> forwardGreeter,
      "route"   -> routerGreeter
    )

  def block(name: String): Semaphore = {
    val barrier = new Semaphore(0)
    actors(name) ! Block(barrier)
    barrier
  }

  @Trace
  def send(name: String, who: String): Unit = {
    val actor = actors(name)
    setAsyncPropagationEnabled(true)
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
    setAsyncPropagationEnabled(true)
    activeSpan().setSpanName("leak all the things")
    tellGreeter ! WhoToGreet(who)
    tellGreeter ! Leak(leak)
  }

  @Trace
  def schedule(): Semaphore = {
    val barrier = new Semaphore(0)
    setAsyncPropagationEnabled(true)
    activeSpan().setSpanName("schedulerSpan")
    scheduler ! Schedule(barrier)
    barrier
  }
}

object PekkoActors {

  // The way to terminate an actor system has changed between versions
  val terminate: (ActorSystem) => Unit = {
    val t =
      try {
        ActorSystem.getClass.getMethod("terminate")
      } catch {
        case _: Throwable =>
          try {
            ActorSystem.getClass.getMethod("awaitTermination")
          } catch {
            case _: Throwable => null
          }
      }
    if (t ne null) {
      { system: ActorSystem =>
        t.invoke(system)
      }
    } else {
      { _ => ??? }
    }
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

object Scheduler {
  def props: Props = Props[Scheduler]

  final case class Schedule(barrier: Semaphore)
  final case class Execute(barrier: Semaphore)
}

class Scheduler extends Actor with ActorLogging {
  import Scheduler._
  import context.dispatcher

  def receive = {
    case Schedule(barrier) =>
      context.system.scheduler.scheduleOnce(1.second, self, Execute(barrier))
    case Execute(barrier) =>
      tracedChild(barrier)
  }

  @Trace
  def tracedChild(barrier: Semaphore): Unit = {
    activeSpan().setSpanName("scheduledOperationSpan")
    barrier.release(1)
  }
}
