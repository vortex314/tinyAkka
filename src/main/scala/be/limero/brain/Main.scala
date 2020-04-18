package be.limero.brain

import java.net.InetAddress

import org.json4s.native.JsonMethods._
import org.json4s.{DefaultFormats, _}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable

object Ip {
  val ipAddress = InetAddress.getLocalHost.getHostAddress
}

class Poller(thread: NanoThread) extends Actor(thread) {
  var idx = 0
  val ticker = TimerSource(thread, 1, 500, true)
  var requestables = mutable.Buffer[Requestable]()

  def apply(requestable: Requestable*): Poller = {
    requestable.flatMap((rq)=> requestables+=rq)
    this
  }

  def init() = {
    ticker >> (_ => {
      idx += 1
      if (idx == requestables.size) idx = 0
      if ( requestables.size !=0) requestables(idx).request()
    })
  }

}

object Main {
  val log: Logger = LoggerFactory.getLogger(classOf[Thread])

  val mainThread = NanoThread("main")
  val mqttThread = NanoThread("mqtt")
  val mqtt = new Mqtt(mqttThread)
  val timer = TimerSource(mainThread, 1, 1000, true)
  val upTime = new LambdaSource[Long](() => Sys.millis)
  val ipAddress = new LambdaSource[String](() => Ip.ipAddress)
  val poller = new Poller(mainThread)

  def convertToJson[T](json: String)(implicit fmt: Formats = DefaultFormats, mf: Manifest[T]): T =
    Extraction.extract(parse(json))

  def main(args: Array[String]): Unit = {

    mqtt.init
    poller.init
    val vs = new ValueSource[Int](1)
    vs >> mqtt.toTopic[Int]("system/counter")
    upTime >> mqtt.toTopic[Long]("system/upTime")
    ipAddress >> mqtt.toTopic[String]("wifi/ipAddress")
    timer >> (_ => {
      vs() = vs() + 1
      log.info("timer event " + vs())
    })
    poller(upTime, ipAddress)
    mainThread.start
    mqttThread.start
    vs() = 2
  }

}