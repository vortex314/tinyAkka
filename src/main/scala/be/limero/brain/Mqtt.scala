package be.limero.brain


import java.net.InetAddress

import org.eclipse.paho.client.mqttv3._
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization
import org.json4s.native.Serialization.write
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag

case class MqttMsg(topic: String, message: String)

class Mqtt(thread: NanoThread) extends Actor(thread) with MqttCallback {
  val log: Logger = LoggerFactory.getLogger(classOf[Mqtt])

  val outgoing = Sink[MqttMsg](20)
  outgoing.async(thread, (mm) => publish(mm))
  val incoming: QueueFlow[MqttMsg] = QueueFlow[MqttMsg](10)
  val ip = InetAddress.getLocalHost
  val hostname = ip.getHostName
  val srcPrefix = "src/" + hostname + "/"
  val dstPrefix = "dst/" + hostname + "/"
  val persistence = new MemoryPersistence
  var client: MqttClient = null
  var subscribedTopics = ListBuffer[String]()
  val brokerUrl = "tcp://limero.ddns.net:1883"

  def init = {
    try {
      // mqtt client with specific url and client id
      outgoing.async(thread, (mm) => publish(mm))
      incoming >> (mm => log.debug("MQTT RXD " + mm.topic))
      incoming.async(thread)
      subscribedTopics+= dstPrefix+"#"
      initClient
    }
    catch {
      case e: MqttException => println("Mqtt init() exception: " + e)
    }
  }

  def initClient = {
    try {
      client = new MqttClient(brokerUrl, MqttClient.generateClientId, persistence)
      val connOpts = new MqttConnectOptions
      connOpts.setCleanSession(true)
      connOpts.setAutomaticReconnect(true)
      connOpts.setKeepAliveInterval(3000)
      connOpts.setMaxInflight(10)
      client.connect(connOpts)
      client.setCallback(this)
      resubscribeAll
    }
    catch {
      case e: MqttException => println("Mqtt init() exception: " + e)
    }
  }

  override def messageArrived(topic: String, message: MqttMessage): Unit = {
    log.debug("Receiving Data, Topic : %s, Message : %s".format(topic, message))
    try {
      incoming.on(MqttMsg(topic, message.toString))
    } catch {
      case ex:Exception => log.error("MQTT reception fails",ex)
    }
  }

  override def connectionLost(cause: Throwable): Unit = {
    log.warn("MQTT lost connection, reconnecting")
    client.close(true)
    initClient
  }
  override def deliveryComplete(token: IMqttDeliveryToken): Unit = {
  }

  def resubscribeAll = {
    subscribedTopics.foreach((topic) => {
      log.info(" subscribing to " + topic)
      try {
        client.subscribe(topic)
      } catch {
        case ex: Exception => log.error("subscribe failed.", ex)
      }
    })
  }

  def subscribe(topic: String) = {
    if (subscribedTopics.forall(t => {
      t.compareTo(topic) != 0
    })) {
      subscribedTopics += topic
      log.info(" subscribing to " + topic)
      try {
        client.subscribe(topic)
      } catch {
        case ex: Exception => log.error("subscribe failed.", ex)
      }
    }
  }

  def publish(mm: MqttMsg) = {
    if ( client.isConnected ) {
      val msgTopic = client.getTopic(mm.topic)
      val message = new MqttMessage(mm.message.getBytes("utf-8"))
      message.setQos(0)
      try {
        msgTopic.publish(message)
      } catch {
        case ex: Exception => log.error("publish failed.", ex)
      }
    } else {
      log.info(" no publish connection lost.")
    }
  }

  def anyToJson[T](value: T): String = {
    implicit val formats = Serialization.formats(NoTypeHints)
    write(value)
  }

  def jsonToAny[T](json: String)(m:Manifest[T]): T = {
    implicit val fmt: Formats = DefaultFormats
 //   implicit  val mf:Manifest[T]=manifest[T]
    val jsonString = """{ "x":""" + json + """ }""" //JSON4S bug doesn't parse primitives
    val parsed = parse(jsonString)
    Extraction.extract(parsed \ "x")(fmt,m)
  }

  def to[T](topic: String)(implicit ct: ClassTag[T]): Sink[T] = {
    Sink[T](3, t => {
      if ( topic.startsWith("dst/")) outgoing.on(MqttMsg(topic, anyToJson[T](t)))
      else outgoing.on(MqttMsg(srcPrefix+topic, anyToJson[T](t)))
    })
  }

  def from[T:Manifest](topic: String): Source[T] = {
    val valueFlow = new ValueFlow[T]()

    subscribe(topic)
    incoming >> ((mm) => {
      if (mm.topic == topic) {
        val v = jsonToAny[T](mm.message)(manifest[T])
        try {
          valueFlow.on(v)
        } catch {
          case ex:Exception => log.error("exception in handling mqtt message ")
        }
      }
    })
    valueFlow
  }
}
