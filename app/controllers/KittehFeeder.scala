package controllers

import play.api._
import play.api.mvc._
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.{Promise => PlayPromise, AkkaPromise => PlayAkkaPromise}
import play.api.libs.iteratee._
import play.api.libs.iteratee.Enumerator.Pushee
import akka.actor._
import akka.dispatch.{ExecutionContext, Promise => AkkaPromise}
import hub.HubActor
import hub.HubActor._

object FeedMaster {

  case class EstablishFeed(enumeratorPromise: AkkaPromise[Enumerator[CheckPullRequest]])

}

class FeedMaster(hub: ActorRef) extends Actor {
  import FeedMaster._
  import FeedActor._
  def receive = {
    case EstablishFeed(enumeratorPromise) => {
      val feeder = context.actorOf(Props[FeedActor])
      val enumerator = Enumerator.pushee[CheckPullRequest](
        // onStart
        pushee => feeder ! ConnectionOpen(pushee),
        // onComplete
        feeder ! ConnectionDone,
        // onError
        (msg, in) => feeder ! ConnectionError(msg)
      )
      hub ! RegisterListener(feeder)
      enumeratorPromise.complete(Right(enumerator))
    }
  }
}

object FeedActor {

  case class ConnectionOpen(pushee: Pushee[CheckPullRequest])
  case object ConnectionDone
  case class ConnectionError(msg: String)

  sealed trait ConnectionState
  case object Disconnected extends ConnectionState
  case object Connected extends ConnectionState

}

class FeedActor extends Actor with FSM[FeedActor.ConnectionState, Option[Pushee[CheckPullRequest]]] {
  import FSM._
  import FeedActor._
  startWith(Disconnected, None)
  when(Disconnected) {
    case Event(ConnectionOpen(pushee), _) => goto(Connected) using Some(pushee)
    case Event(_: CheckPullRequest, _) => stay // Drop message if not connected
  }
  when(Connected) {
    case Event(ConnectionDone, _) => stop(Shutdown)
    case Event(ConnectionError(msg), _) => stop(Failure("Connection error: " + msg))
    case Event(cpr: CheckPullRequest, Some(pushee)) => { pushee.push(cpr) ; stay }
  }
  onTermination {
    case StopEvent(_, _, Some(pushee)) => pushee.close() // May already be closed, but should be fine to call twice.
  }
}

class KittehFeeder(system: ActorSystem, hub: ActorRef) extends Controller {

  implicit private val executionContext = system.dispatcher

  val master = system.actorOf(Props(new FeedMaster(hub)), "feedmaster")

  /** Make a promised Enumerator available immediately. */
  private def flattenPromisedEnumerator[E](futEnum: PlayPromise[Enumerator[E]]): Enumerator[E] = new Enumerator[E] {
    def apply[A](i: Iteratee[E, A]): PlayPromise[Iteratee[E, A]] = futEnum.flatMap(_.apply(i))
  }

  private def establishFeed(implicit executor: ExecutionContext): Enumerator[CheckPullRequest] = {
    val enumeratorAkkaPromise = AkkaPromise[Enumerator[CheckPullRequest]]
    val enumeratorPlayPromise = new PlayAkkaPromise(enumeratorAkkaPromise)
    master ! FeedMaster.EstablishFeed(enumeratorAkkaPromise)
    flattenPromisedEnumerator(enumeratorPlayPromise)
  }

  def index = WebSocket.using[String] { request =>
    val in = Iteratee.consume[String]()
    val out = establishFeed.map {
      case CheckPullRequest(user, repo, number) => user + "," + repo + "," + number // FIXME: Verify/escape input
    }
    (in, out)
  }

}