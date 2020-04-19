package be.limero.brain

import java.net.InetAddress

import org.json4s.native.JsonMethods._
import org.json4s.{DefaultFormats, _}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable

object Ip {
  val ipAddress = InetAddress.getLocalHost.getHostAddress
}


class Echo(thread: NanoThread) extends Actor(thread) {
  val in = new Sink[Int](3)
  val out = new ValueSource[Int](0)
  in.async(thread, (i) => out() = i + 1)
}

class Sender(thread: NanoThread, max: Int) extends Actor(thread) {
  val in = new Sink[Int](3)
  val out = new ValueSource[Int](0)
  in.async(thread, (i) => {
    out() = i
    if ((i % max) == 0) NanoAkka.log.info(" handled " + i + " messages ")
  })
}

class Poller(thread: NanoThread) extends Actor(thread) {
  var idx = 0
  val ticker = TimerSource(thread, 1, 500, true)
  var requestables = mutable.Buffer[Requestable]()

  def apply(requestable: Requestable*): Poller = {
    requestable.flatMap((rq) => requestables += rq)
    this
  }

  def init() = {
    ticker >> (_ => {
      idx += 1
      if (idx == requestables.size) idx = 0
      if (requestables.size != 0) requestables(idx).request()
    })
  }

}

case class Scale(x1: Int, x2: Int, y1: Int, y2: Int) extends Flow[Int, Int] {
  def on(i: Int) = {
    emit(y1 + (i * (y2 - y1)) / (x2 - x1))
  }
}

case class Step(step: Int) extends Flow[Int, Int] {
  def on(value: Int) = {
    val v = (value / step) * step
    if ((v % step) != 0) log.warn("Step failed " + v + ":" + value)
    emit(v)
  }
}

case class Zero(zero: Int) extends Flow[Int, Int] {
  def on(value: Int) = {
    if (Math.abs(value) < zero) emit(0)
    else emit(value)
  }
}


case class Log[T](msg: String) extends Flow[T, T] {
  def on(t: T) = {
    log.info(msg + t)
    emit(t)
  }
}

case class Changed[T](timeout: Int, var oldValue: T) extends Flow[T, T] {
  private var lastEmit: Long = Sys.millis

  def on(value: T) = {
    if ((value != oldValue) || (lastEmit + timeout < Sys.millis)) {
      oldValue = value
      lastEmit = Sys.millis
      emit(value)
    }
  }
}

object Main {
  val log: Logger = LoggerFactory.getLogger(classOf[Thread])

  val mainThread = NanoThread("main")
  val mqttThread = NanoThread("mqtt")
  val mqtt = new Mqtt(mqttThread)
  val timer = TimerSource(mainThread, 1, 10000, true)
  val upTime = new LambdaSource[Long](() => Sys.millis)
  val ipAddress = new LambdaSource[String](() => Ip.ipAddress)
  val poller = new Poller(mainThread)
  val echo = new Echo(mainThread)
  val sender = new Sender(mainThread, 1000000)

  def convertToJson[T](json: String)(implicit fmt: Formats = DefaultFormats, mf: Manifest[T]): T =
    Extraction.extract(parse(json))

  def main(args: Array[String]): Unit = {

    mqtt.init
    poller.init
    val vs = new ValueSource[Int](1)
    vs >> mqtt.to[Int]("system/counter")
    upTime >> mqtt.to[Long]("system/upTime")
    ipAddress >> mqtt.to[String]("wifi/ipAddress")
    mqtt.from[Int]("system/counter") >> (i => {
      log.info("i=" + i)
    })
    timer >> (_ => {
      vs() = vs() + 1
      log.info("timer event " + vs())
    })
    mqtt.from[Int]("src/remote/remote/potLeft") >>
      Scale(0, 1023, -90, +90) >>
      Step(5) >>
      Zero(10) >>
 //     Changed(1000, 0) >>
      Log[Int]("angleTarget:") >>
      mqtt.to[Int]("dst/drive/stepper/angleTarget")

    mqtt.from[Int]("src/remote/remote/potRight") >>
      Scale(0, 1023, -200, +200) >>
      Step(10) >>
      Zero(20) >>
 //     Changed(1000, 0) >>
      Log[Int]("rpmTarget:") >>
      mqtt.to[Int]("dst/drive/motor/rpmTarget")

    mqtt.from[Boolean]("src/remote/remote/buttonLeft") >>
 //     Changed[Boolean](1000, false) >>
      Log[Boolean]("buttonLeft:") >>
      mqtt.to[Boolean]("dst/cutter/cutter/on")

    poller(upTime, ipAddress)
    echo.out >> sender.in
    sender.out >> echo.in
    //    echo.in.on(1)
    mainThread.start
    mqttThread.start

    vs() = 2
  }

}