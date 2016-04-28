package com.lihaoyi.workbench

import akka.actor.ActorDSL._
import akka.actor.{Actor, ActorRef, ActorSystem}
import com.typesafe.config.ConfigFactory
import sbt.IO
import spray.http.HttpHeaders._
import spray.http.HttpMethods._
import spray.http.{AllOrigins, HttpResponse}
import spray.routing.{RequestContext, SimpleRoutingApp}
import upickle.Js
import upickle.default.{Reader, Writer, write, writeJs}

import scala.concurrent.Future
import scala.concurrent.duration._

class Server(url: String, port: Int, bootSnippet: String) extends SimpleRoutingApp {

  val corsHeaders: List[ModeledHeader] =
    List(
      `Access-Control-Allow-Methods`(OPTIONS, GET, POST),
      `Access-Control-Allow-Origin`(AllOrigins),
      `Access-Control-Allow-Headers`("Origin, X-Requested-With, Content-Type, Accept, Accept-Encoding, Accept-Language, Host, Referer, User-Agent"),
      `Access-Control-Max-Age`(1728000)
    )

  implicit val system = ActorSystem(
    "Workbench-System",
    config = ConfigFactory.load(ActorSystem.getClass.getClassLoader),
    classLoader = ActorSystem.getClass.getClassLoader
  )

  /**
   * The connection from workbench server to the client
   */
  object Wire extends autowire.Client[Js.Value, Reader, Writer] with ReadWrite{
    def doCall(req: Request): Future[Js.Value] = {
      longPoll ! Js.Arr(writeJs(req.path), Js.Obj(req.args.toSeq:_*))
      Future.successful(Js.Null)
    }
  }

  /**
   * Actor meant to handle long polling, buffering messages or waiting actors
   */
  private val longPoll = actor(new Actor{
    var waitingActor: Option[ActorRef] = None
    var queuedMessages = List[Js.Value]()

    /**
     * Flushes returns nothing to any waiting actor every so often,
     * to prevent the connection from living too long.
     */
    case object Clear
    import system.dispatcher

    system.scheduler.schedule(0 seconds, 10 seconds, self, Clear)
    def respond(a: ActorRef, s: String) = {
      a ! HttpResponse(
        entity = s,
        headers = corsHeaders
      )
    }

    def receive: PartialFunction[Any, Unit] = {
      case (x: Any) ⇒
        (x, waitingActor, queuedMessages) match {
          case (a: ActorRef, _, Nil) =>
            // Even if there's someone already waiting,
            // a new actor waiting replaces the old one
            waitingActor = Some(a)

          case (a: ActorRef, None, msgs) =>
            respond(a, upickle.json.write(Js.Arr(msgs:_*)))
            queuedMessages = Nil

          case (msg: Js.Arr, None, msgs) =>
            queuedMessages = msg :: msgs

          case (msg: Js.Arr, Some(a), Nil) =>
            respond(a, upickle.json.write(Js.Arr(msg)))
            waitingActor = None

          case (Clear, waitingOpt, Nil) =>
            waitingOpt.foreach(respond(_, upickle.json.write(Js.Arr())))
            waitingActor = None
        }
    }
  })

  /**
   * Simple spray server:
   *
   * - /workbench.js is hardcoded to be the workbench javascript client
   * - Any other GET request just pulls from the local filesystem
   * - POSTs to /notifications get routed to the longPoll actor
   */
  startServer(url, port) {
    get {
      path("workbench.js") {
        complete {
          val body = IO.readStream(
            getClass.getClassLoader.getResourceAsStream("client-opt.js")
          )
          s"""
          (function(){
            $body

            com.lihaoyi.workbench.WorkbenchClient().main(${write(bootSnippet)}, ${write(url)}, ${write(port)})
          }).call(this)
          """
        }
      } ~
      getFromDirectory(".")

    } ~
    post {
      path("notifications")((ctx: RequestContext) =>
          longPoll ! ctx.responder
      )
    }
  }

  def kill() = system.shutdown()
}