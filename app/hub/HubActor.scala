package hub

import akka.actor.Actor
import akka.actor.ActorRef
import play.api.libs.iteratee.Enumerator.Pushee

object HubActor {
  case class RegisterListener(listener: ActorRef)
  case class CheckPullRequest(user: String, repo: String, number: String)
}

class HubActor extends Actor {
  import HubActor._
  private var listeners: List[ActorRef] = Nil
  def receive = {
  	case cpr: CheckPullRequest => {
  	  listeners = listeners.filter(!_.isTerminated)
      for (l <- listeners) {
        l ! cpr
      }
  	}
    case RegisterListener(l) => {
      listeners = l::listeners
    }
  }
}