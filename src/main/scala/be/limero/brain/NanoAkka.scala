package be.limero.brain

import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}

import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ListBuffer

trait Subscriber[T] {
  def on(in: T): Unit
}

trait Invoker {
  def invoke(): Unit
}

trait Requestable {
  def request(): Unit
}


case class SubscriberFunction[T](callback: T => Unit) extends Subscriber[T] {
  def on(t: T): Unit = callback(t)
}

class ValueSource[T](var value: T) extends Source[T] {
  var pass = true

  def request = {
    emit(value)
  }

  /*  private var _value:T=_

    def value = _value
  def value_= ( v:T ):Unit = {
    _value = v
    if ( pass ) emit(_value)
  }*/
  def update(v: T): Unit = {
    value = v
    if (pass) emit(value)
  }

}

case class TimerMsg(id: Int)

case class TimerSource(thread: NanoThread, id: Int, interval: Int, repeat: Boolean)
  extends Source[TimerMsg] {
  thread.timerSources += this
  var expiresOn: Long = millis() + interval

  def millis(): Long = System.currentTimeMillis()

  def timeout(): Long = expiresOn - millis()

  def request(): Unit = {
    if (repeat) {
      expiresOn += interval
      if (expiresOn < millis()) expiresOn = millis() + interval
    } else {
      expiresOn = Long.MaxValue
    }
    emit(TimerMsg(id))
  }
}

case class Sink[T](var callback: T => Unit) extends Subscriber[T] with Invoker {
  val log: Logger = LoggerFactory.getLogger(classOf[NanoThread])

  var queue = new ArrayBlockingQueue[T](100)
  var thread: NanoThread = _
  callback = _ => log.warn("no handler assigned to Sink")

  def on(t: T): Unit = {
    if (thread == null) callback(t)
    else {
      queue.put(t)
      thread.enqueue(this)
    }
  }

  def request(): Unit = invoke()

  def invoke(): Unit = {
    val t = queue.poll()
    callback(t)
  }

  def async(thread: NanoThread, callback: T => Unit): Unit = {
    this.thread = thread
    this.callback = callback
  }

  def sync(callback: T => Unit): Unit = {
    thread = null
    this.callback = callback
  }
}

case class NanoThread(name: String) extends Thread {
  var workQueue = new ArrayBlockingQueue[Invoker](100)
  var timerSources: ListBuffer[TimerSource] = ListBuffer[TimerSource]()

  def enqueue(invoker: Invoker): Unit = {
    workQueue.put(invoker)
  }

  override def run(): Unit = {
    while (true) {
      // calc smallest timeout
      var timeout = 1000L
      var timerSource: TimerSource = null
      timerSources.foreach(ts => if (ts.timeout() < timeout) {
        timeout = ts.timeout()
        timerSource = ts
      })
      // waith for work and sleep
      val invoker = workQueue.poll(timeout, TimeUnit.MILLISECONDS)
      if (invoker != null) {
        invoker.invoke()
      } else {
        if (timerSource != null) timerSource.request()
      }
    }
  }

}

trait Publisher[T] {
  def subscribe(subscriber: Subscriber[T])

  def >>(subscriber: Subscriber[T]): Unit = subscribe(subscriber)

  def >>(f: T => Unit): Unit = subscribe(new SubscriberFunction[T](f))
}

class Source[OUT] extends Publisher[OUT] {
  val log: Logger = LoggerFactory.getLogger(classOf[NanoThread])

  var subscribers: ListBuffer[Subscriber[OUT]] = ListBuffer[Subscriber[OUT]]()

  def subscribe(subscriber: Subscriber[OUT]): Unit = {
    subscribers += subscriber
  }

  def emit(out: OUT): Unit = {
    subscribers.foreach(sub => sub.on(out))
  }
}

abstract class Flow[IN, OUT] extends Source[OUT] with Subscriber[IN] {}

case class QueueFlow[T](depth: Int) extends Flow[T, T] {
  def on(t: T): Unit = {}
}

object QueueFlow

object Flow

class ValueFlow[T] extends Flow[T, T] {
  private var t: T = _
  var pass = false

  def request = emit(t)

  def update(t2: T) = {
    t = t2
    if (pass) emit(t)
  }

  override def on(in: T): Unit = {
    t = in
    emit(in)
  }

}

class Actor(thread: NanoThread) {}

// JSON conversion

case class MqttMsg(topic: String, message: String)


object Source {
  val log: Logger = LoggerFactory.getLogger(classOf[NanoThread])

  def main(args: Array[String]): Unit = {
    val source: Source[Int] = new Source[Int]
    val sink = Sink[Int](i => log.info("i:" + i))

    source >> sink
    source.emit(1)
    val thread = NanoThread("main")
    val ts = TimerSource(thread, 1, 1000, repeat = true)
    ts >> (_ => log.info("timer event "))
    thread.run()
  }
}
