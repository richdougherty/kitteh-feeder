package controllers

import play.api._
import play.api.mvc._
import akka.actor._
import hub._

object Application extends Controller {
  
  def robots = Action {
    Ok("User-agent: *\nDisallow: /")
  }
  
  val system = ActorSystem("HelloWorld")

  val hub = system.actorOf(Props[HubActor], "hub")
  val githubHook = new GithubHook(hub)
  val kittehFeeder = new KittehFeeder(system, hub)
 
}