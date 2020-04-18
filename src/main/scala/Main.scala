import java.text.SimpleDateFormat

import be.limero.brain.{Mqtt, NanoThread, TimerSource, ValueSource}
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.DefaultFormats
import org.json4s.native.Serialization
import org.slf4j.{Logger, LoggerFactory}

class Main {

}

case class X(x:AnyRef)

object Main {
  val log: Logger = LoggerFactory.getLogger(classOf[Thread])

  val mainThread=NanoThread("main")
  val mqtt=new Mqtt(mainThread)
  val timer=TimerSource(mainThread,1,1000,true)

  def convertToJson[T](json: String)(implicit fmt: Formats = DefaultFormats, mf: Manifest[T]): T =
    Extraction.extract(parse(json))

  def jsonToAny[T](json:String) (implicit fmt: Formats = DefaultFormats, mf: Manifest[T]): T = {
    val jsonString ="""{ "x":""" + json +""" }""" //JSON4S bug doesn't parse primitives
    val parsed = parse(jsonString)
    Extraction.extract(parsed  \ "x" )
  }


  def main(args: Array[String]): Unit = {

    val prim="122"
    val jsonString = """{ "x":""" + prim +""" }"""


    val v1 = jsonToAny[Int]("122")
    val v2 = jsonToAny[Double]("1.23")
    val v3 = jsonToAny[Boolean]("true")
    val v4 = jsonToAny[String]("\"string\"")
    println("hello world "+v1+":"+v2+":"+v3+":"+v4+":")
    val vs = new ValueSource[Int](1)
    vs >> mqtt.toTopic[Int]("valueSource")
    timer >> (_ => {
      log.info("timer event ")
      vs() = vs.value + 1
    })
    mainThread.start
    vs() =2
  }
}