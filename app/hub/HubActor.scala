package hub

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import play.api.libs.iteratee.Enumerator.Pushee

object HubActor {
  case class RegisterListener(listener: ActorRef)
  case class CheckPullRequest(user: String, repo: String, number: String)
}

class HubActor extends Actor with ActorLogging {
  import HubActor._
  private var listeners: List[ActorRef] = Nil
  def receive = {
  	case cpr: CheckPullRequest => {
      log.debug("Received " + cpr)
  	  listeners = listeners.filter(!_.isTerminated)
      for (l <- listeners) {
        log.debug("Sending " + cpr + " to " + l)
        l ! cpr
      }
  	}
    case RegisterListener(l) => {
      log.debug("Registering " + l)
      listeners = l::listeners
    }
  }
}