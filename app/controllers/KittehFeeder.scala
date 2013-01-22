package controllers

import play.api._
import play.api.mvc._
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.{Promise, AkkaPromise}
import play.api.libs.iteratee._
import play.api.libs.iteratee.Enumerator.Pushee
import akka.actor._
import akka.dispatch.ExecutionContext
import akka.event.Logging
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout
import hub.HubActor
import hub.HubActor._

object FeedMaster {
  case object EstablishFeed
  case class FeedEnumerator(enumerator: Enumerator[CheckPullRequest])
}

class FeedMaster(hub: ActorRef) extends Actor with ActorLogging {
  import FeedMaster._
  import FeedActor._
  def receive = {
    case EstablishFeed => {
      log.debug("Master establishing feed")
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
      sender ! FeedEnumerator(enumerator)
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
  val log = Logging(system, "/feed/kitteh")

  implicit private val executionContext = system.dispatcher

  val master = system.actorOf(Props(new FeedMaster(hub)), "feedmaster")

  /** Make a promised Enumerator available immediately. */
  private def flattenPromisedEnumerator[E](futEnum: Promise[Enumerator[E]]): Enumerator[E] = new Enumerator[E] {
    def apply[A](i: Iteratee[E, A]): Promise[Iteratee[E, A]] = futEnum.flatMap(_.apply(i))
  }

  private def establishFeed(implicit executor: ExecutionContext): Enumerator[CheckPullRequest] = {
    log.debug("Establishing feed")
    implicit val timeout = Timeout(5 seconds)
    val feedFuture = (master ? FeedMaster.EstablishFeed).mapTo[FeedMaster.FeedEnumerator]
    flattenPromisedEnumerator(new AkkaPromise(feedFuture.map(_.enumerator)))
  }

  def index = WebSocket.using[String] { request =>
    log.debug("Kitteh connection")
    val in = Iteratee.consume[String]()
    val out = establishFeed.map {
      case CheckPullRequest(user, repo, number) => user + "," + repo + "," + number // FIXME: Verify/escape input
    }
    (in, out)
  }

}