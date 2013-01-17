package controllers

import play.api._
import play.api.mvc._
import play.api.libs.iteratee._
import play.api.libs.iteratee.Input._
import play.api.libs.iteratee.Done
import play.api.libs.json._
import akka.actor.ActorRef
import hub.HubActor

object GithubHook {
  
  private val maxPayloadSize = 100 * 1024

  implicit def bodyMappable[A](source: BodyParser[A]) = new AnyRef {
    def mapBody[B](f: A => Either[Result, B]): BodyParser[B] =
      source.flatMap { a => BodyParser { request => Done(f(a), Empty) } }
    def mapBodyWithRequest[B](f: (RequestHeader, A) => Either[Result, B]): BodyParser[B] =
      source.flatMap { a => BodyParser { request => Done(f(request, a), Empty) } }
  }

  val eventParser: BodyParser[GithubEvent] = BodyParsers.parse.tolerantFormUrlEncoded(maxPayloadSize) mapBodyWithRequest extractGithubEventRaw mapBody refineGithubEventOrError
  
  case class GithubEventRaw(event: String, payload: JsValue)

  /** 
   * Given the request headers and the form content, try to extract the Github event type and
   * the form's JSON payload.
   */
  def extractGithubEventRaw(request: RequestHeader, form: Map[String,Seq[String]]): Either[Result, GithubEventRaw] = {
    def badResult = Left(Results.BadRequest)
    request.headers.get("X-Github-Event").map { eventName =>
      form.get("payload").map { payloadSeq =>
        payloadSeq.toList match {
          case List(payload) => try {
            Right(GithubEventRaw(eventName, Json.parse(payload)))
          } catch { case e: Exception => badResult }
          case _ => badResult
        }
      } getOrElse {
        badResult
      }
    } getOrElse {
      badResult
    }
  }
  
  trait GithubEvent
  case class PullRequestEvent(action: String, number: String, repoOwner: String, repo: String) extends GithubEvent
  case class IssueCommentEvent(action: String, issueNumber: String, pullRequest: Boolean, body: String, repoOwner: String, repo: String) extends GithubEvent

  // FIXME: Add to Play
  val UNPROCESSABLE_ENTITY = 422
  /** Generates a ‘422 UNPROCESSABLE_ENTITY’ result. */
  val UnprocessableEntity = new Results.Status(UNPROCESSABLE_ENTITY)

  def refineGithubEvent(raw: GithubEventRaw): Either[Throwable, Option[GithubEvent]] = {
    scala.util.control.Exception.catching[Option[GithubEvent]](classOf[Exception]).either {
      raw match {
        case GithubEventRaw("pull_request", payload) =>
          Some(PullRequestEvent(
            (payload \ "action").as[String],
            (payload \ "number").as[Int].toString,
            (payload \ "repository" \ "owner" \ "login").as[String],
            (payload \ "repository" \ "name").as[String]))
        case GithubEventRaw("issue_comment", payload) =>
          Some(IssueCommentEvent(
            (payload \ "action").as[String],
            (payload \ "issue" \ "number").as[Int].toString,
            (payload \ "issue").as[Map[String, JsValue]].contains("pull_request"),
            (payload \ "comment" \ "body").as[String],
            (payload \ "repository" \ "owner" \ "login").as[String],
            (payload \ "repository" \ "name").as[String]))
        case _ => None
      }
    }
  }
  def refineGithubEventOrError(raw: GithubEventRaw): Either[Result, GithubEvent] = {
    refineGithubEvent(raw).fold(
      _ => Left(Results.BadRequest), // FIXME: Don't discard information in Throwable
      _.toRight(UnprocessableEntity))
  }
  
  def handlingForEvent(event: GithubEvent): Option[HubActor.CheckPullRequest] = event match {
    case PullRequestEvent("opened" | "synchronize", number, repoOwner, repo) =>
      Some(HubActor.CheckPullRequest(repoOwner, repo, number))
    case IssueCommentEvent("created", issueNumber, true, body, repoOwner, repo) if body.contains("PLS REBUILD") =>
      Some(HubActor.CheckPullRequest(repoOwner, repo, issueNumber))
    case _ => None
  }
}

class GithubHook(hub: ActorRef) extends Controller {
  import GithubHook._

  def index = Action(eventParser) { request =>
    handlingForEvent(request.body).foreach(hub ! _)
    Ok("")
  }

}