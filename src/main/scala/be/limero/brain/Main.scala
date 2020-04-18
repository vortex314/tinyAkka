package be.limero.brain

import java.net.InetAddress

import org.json4s.native.JsonMethods._
import org.json4s.{DefaultFormats, _}
import org.slf4j.{Logger, LoggerFactory}

object Ip {
  val ipAddress = InetAddress.getLocalHost.getHostAddress
}
object Main {
  val log: Logger = LoggerFactory.getLogger(classOf[Thread])

  val mainThread = NanoThread("main")
  val mqtt = new Mqtt(mainThread)
  val timer = TimerSource(mainThread, 1, 1000, true)
  val upTime = new LambdaSource[Long](()=>Sys.millis)
  val ipAddress = new LambdaSource[String](()=>Ip.ipAddress)

  def convertToJson[T](json: String)(implicit fmt: Formats = DefaultFormats, mf: Manifest[T]): T =
    Extraction.extract(parse(json))

  def main(args: Array[String]): Unit = {

    mqtt.init

    val vs = new ValueSource[Int](1)
    vs >> mqtt.toTopic[Int]("system/counter")
    upTime >> mqtt.toTopic[Long]("system/upTime")
    ipAddress >> mqtt.toTopic[String]("wifi/ipAddress")
    timer >> (_ => {
      vs() = vs() + 1
      log.info("timer event "+vs.value)
      upTime.request()
      ipAddress.request()
    })
    mainThread.start
    vs() = 2
  }

}