package be.limero.brain


import java.net.InetAddress

import org.eclipse.paho.client.mqttv3._
import org.eclipse.paho.client.mqttv3.persist.{MemoryPersistence, MqttDefaultFilePersistence}
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization
import org.json4s.native.Serialization.{read, write}
import org.slf4j.{Logger, LoggerFactory}

import scala.reflect.ClassTag

case class MqttMsg(topic: String, message: String)

class Mqtt(thread: NanoThread) extends Actor(thread) {
  val log: Logger = LoggerFactory.getLogger(classOf[Mqtt])

  val outgoing= Sink[MqttMsg] ((mm) => publish(mm),20)
  val incoming: QueueFlow[MqttMsg] = QueueFlow[MqttMsg](10)
  val ip = InetAddress.getLocalHost
  val hostname = ip.getHostName
  val srcPrefix = "src/" + hostname + "/"
  val dstPrefix = "dst/" + hostname + "/"
  val persistence = new  MemoryPersistence
  var client: MqttClient = null

  def init = {
    val brokerUrl = "tcp://limero.ddns.net:1883"
    try {
      // mqtt client with specific url and client id
      client = new MqttClient(brokerUrl, MqttClient.generateClientId, persistence)
      log.info("MQTT connecting to "+brokerUrl)
      client.connect()
      outgoing.async(thread, (mm) => publish(mm))
      log.info("MQTT subscribing to "+dstPrefix+"#")
      client.subscribe(dstPrefix + "#")
      incoming >> ( mm=> log.info("MQTT RXD "+mm.topic))
      val callback = new MqttCallback {

        override def messageArrived(topic: String, message: MqttMessage): Unit = {
          log.info("Receiving Data, Topic : %s, Message : %s".format(topic, message))
          incoming.on(MqttMsg(topic,message.toString))
        }
        override def connectionLost(cause: Throwable): Unit = {
          log.warn("MQTT lost connection, reconnecting")
          client.reconnect()
          client.subscribe(dstPrefix + "#")
        }
        override def deliveryComplete(token: IMqttDeliveryToken): Unit = {
        }
      }
      //Set up callback for MqttClient
      client.setCallback(callback)
    }
    catch {
      case e: MqttException => println("Mqtt init() exception: " + e)
    }
  }

  def publish(mm: MqttMsg) = {
    val msgTopic = client.getTopic(mm.topic)
    val message = new MqttMessage(mm.message.getBytes("utf-8"))
    msgTopic.publish(message)
  }

  def anyToJson[T](value: T): String = {
    implicit val formats = Serialization.formats(NoTypeHints)
    write(value)
  }

  def jsonToAny[T](json: String): T = {
    implicit val fmt: Formats = DefaultFormats
 //   implicit val mf: Manifest[T]
    val jsonString = """{ "x":""" + json + """ }""" //JSON4S bug doesn't parse primitives
    val parsed = parse(jsonString)
    Extraction.extract(parsed \ "x")
  }

  def toTopic[T](topic: String)(implicit ct:ClassTag[T]): Sink[T] = {
    Sink[T](t => {
      outgoing.on(MqttMsg(srcPrefix + topic, anyToJson[T](t)))
    })
  }
  def fromTopic[T](topic: String): Source[T] = {
    val valueFlow = new ValueFlow[T]()
    incoming >> ((mm) => {
      if (mm.topic == topic) {
        val v = jsonToAny[T](topic)
        valueFlow.on(v)
      }
    })
    valueFlow
  }
}
