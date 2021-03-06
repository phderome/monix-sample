package engine

import akka.actor.{Actor, ActorRef, Props}
import engine.SimpleWebSocketActor.Next
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.execution.Ack.Continue
import monix.execution.cancelables.CompositeCancelable
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.{JsValue, Json, Writes}

import scala.concurrent.duration._
import engine.BackPressuredWebSocketActor._


class SimpleWebSocketActor[T: Writes]
  (producer: Observable[T], out: ActorRef)(implicit s: Scheduler)
  extends Actor {

  def receive: Receive = {
    case Next(jsValue) =>
      out ! jsValue
  }

  private[this] val subscription =
    CompositeCancelable()

  override def preStart(): Unit = {
    super.preStart()

    val source = {
      val initial = Observable.evalOnce(initMessage(now()))
      val obs = initial ++ producer.map(x => Json.toJson(x))
      val timeout = obs.debounce(3.seconds).map(_ => keepAliveMessage(now()))
      Observable.merge(obs, timeout)
    }

    subscription += source.subscribe { jsValue =>
      self ! Next(jsValue)
      Continue
    }
  }

  override def postStop(): Unit = {
    subscription.cancel()
    super.postStop()
  }

  def now(): Long =
    DateTime.now(DateTimeZone.UTC).getMillis
}

object SimpleWebSocketActor {
  /** Utility for quickly creating a `Props` */
  def props[T: Writes](producer: Observable[T], out: ActorRef)
    (implicit s: Scheduler): Props = {

    Props(new SimpleWebSocketActor(producer, out))
  }

  /**
   * Used in order to not confuse self messages versus those
   * sent from the client.
   */
  case class Next(value: JsValue)
}