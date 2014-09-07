package com.reactivedesignpatterns.chapter4

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import akka.testkit.TestProbe
import akka.actor.Props
import akka.actor.ActorRef
import scala.concurrent.duration._
import akka.actor.Actor
import com.reactivedesignpatterns.Scoped._
import scala.util.Try

object EchoServiceSpec {
  import EchoService._

  case class TestSLA(echo: ActorRef, n: Int, maxParallelism: Int, reportTo: ActorRef)
  case object AbortSLATest
  case class SLAResponse(timings: Seq[FiniteDuration], outstanding: Map[String, Deadline])

  class ParallelSLATester extends Actor {

    def receive = {
      case TestSLA(echo, n, maxParallelism, reportTo) =>
        val receiver = context.actorOf(receiverProps(self))
        // prime the request pipeline
        val sendNow = Math.min(n, maxParallelism)
        val outstanding = Map.empty ++ (for (_ <- 1 to sendNow) yield sendRequest(echo, receiver))
        context.become(running(reportTo, echo, n - sendNow, receiver, outstanding, Nil))
    }

    def running(reportTo: ActorRef,
                echo: ActorRef,
                remaining: Int,
                receiver: ActorRef,
                outstanding: Map[String, Deadline],
                timings: List[FiniteDuration]): Receive = {
      case TimedResponse(Response(r), d) =>
        val start = outstanding(r)
        scoped(
          outstanding - r + sendRequest(echo, receiver),
          (d - start) :: timings,
          remaining - 1
        ) { (outstanding, timings, remaining) =>
            if (remaining > 0)
              context.become(running(reportTo, echo, remaining, receiver, outstanding, timings))
            else
              context.become(finishing(reportTo, outstanding, timings))
          }
      case AbortSLATest =>
        context.stop(self)
        reportTo ! SLAResponse(timings, outstanding)
    }

    def finishing(reportTo: ActorRef, outstanding: Map[String, Deadline], timings: List[FiniteDuration]): Receive = {
      case TimedResponse(Response(r), d) =>
        val start = outstanding(r)
        scoped(outstanding - r, (d - start) :: timings) { (outstanding, timings) =>
          if (outstanding.isEmpty) {
            context.stop(self)
            reportTo ! SLAResponse(timings, outstanding)
          } else context.become(finishing(reportTo, outstanding, timings))
        }
      case AbortSLATest =>
        context.stop(self)
        reportTo ! SLAResponse(timings, outstanding)
    }

    val idGenerator = Iterator from 1 map (i => s"test-$i")

    def sendRequest(echo: ActorRef, receiver: ActorRef): (String, Deadline) = {
      val request = idGenerator.next
      echo ! Request(request, receiver)
      request -> Deadline.now
    }
  }

  private def receiverProps(controller: ActorRef) = Props(new ParallelSLATestReceiver(controller))
  private case class TimedResponse(r: Response, d: Deadline)

  // timestamp received replies in a dedicated actor to keep timing distortions low
  private class ParallelSLATestReceiver(controller: ActorRef) extends Actor {
    def receive = {
      case r: Response => controller ! TimedResponse(r, Deadline.now)
    }
  }

}

class EchoServiceSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  import EchoServiceSpec._

  // implicitly picked up to create TestProbes, lazy to only start when used
  implicit lazy val system = ActorSystem("EchoServiceSpec")

  override def afterAll(): Unit = {
    system.shutdown()
  }

  private def echoService(name: String): ActorRef = system.actorOf(Props[EchoService], name)

  "An EchoService" must {
    import EchoService._

    "reply correctly" in {
      val probe = TestProbe()
      val echo = echoService("replyCorrectly")
      echo ! Request("test", probe.ref)
      probe.expectMsg(1.second, Response("test"))
    }

    "keep its SLA" in {
      val probe = TestProbe()
      val echo = echoService("keepSLA")
      val N = 200
      val timings = for (i <- 1 to N) yield {
        val string = s"test$i"
        val start = Deadline.now
        echo ! Request(string, probe.ref)
        probe.expectMsg(100.millis, s"test run $i", Response(string))
        val stop = Deadline.now
        stop - start
      }
      // discard top 5%
      val sorted = timings.sorted
      val ninetyfifthPercentile = sorted.dropRight(N * 5 / 100).last
      info(s"sequential SLA min=${sorted.head} max=${sorted.last} 95th=$ninetyfifthPercentile")
      ninetyfifthPercentile should be < 1.millisecond
    }

    "keep its SLA when used in parallel" in {
      val echo = echoService("keepSLAparallel")
      val probe = TestProbe()
      val N = 10000
      val maxParallelism = 500
      val controller = system.actorOf(Props[ParallelSLATester], "keepSLAparallelController")
      controller ! TestSLA(echo, N, maxParallelism, probe.ref)
      val result = Try(probe.expectMsgType[SLAResponse]).recover {
        case ae: AssertionError =>
          controller ! AbortSLATest
          val result = probe.expectMsgType[SLAResponse]
          info(s"controller timed out, state so far is $result")
          throw ae
      }.get
      // discard top 5%
      val sorted = result.timings.sorted
      val ninetyfifthPercentile = sorted.dropRight(N * 5 / 100).last
      info(s"parallel SLA min=${sorted.head} max=${sorted.last} 95th=$ninetyfifthPercentile")
      ninetyfifthPercentile should be < 2.milliseconds
    }

  }

}