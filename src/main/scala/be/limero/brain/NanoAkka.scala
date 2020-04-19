package be.limero.brain

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}

import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag


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

object NanoAkka {
  val log: Logger = LoggerFactory.getLogger(classOf[NanoThread])
  var bufferPushBusy = 0
  var bufferPopBusy = 0;
  var queueOverflow = 0
  var bufferOverflow = 0
  var bufferCasRetries = 0

  def defaultHandler[T]: T => Unit = _ => log.warn(" no handler specified")
}

class ArrayQueue[T](size: Int)(implicit ct: ClassTag[T]) {
  val log: Logger = LoggerFactory.getLogger(classOf[ArrayQueue[T]])
  var array = new Array[T](size)
  private var readPtr = new AtomicInteger(0)
  private var writePtr = new AtomicInteger(0)

  private def next(idx: Int) = (idx + 1) % size

  private val BUSY = 1 << 16

  def push(t: T): Boolean = {
    var cnt = 0
    while (cnt < 5) {
      var expected = writePtr.get()
      if ((expected & BUSY) != 0) {
        NanoAkka.bufferPushBusy += 1
        log.warn("BUSY")
        return false
      }
      var desired = next(expected)
      if (desired == readPtr.get() % size) {
        NanoAkka.bufferOverflow += 1
        log.warn("buffer overflow " + ct.toString() + " on" + t.toString)
        return false
      }
      desired |= BUSY
      if (writePtr.weakCompareAndSet(expected, desired)) {
        expected = desired
        desired &= ~BUSY
        array(desired) = t
        while (writePtr.weakCompareAndSet(expected, desired) == false) {
          log.warn("writePtr remove busy failed")
          NanoAkka.bufferCasRetries += 1
          Thread.sleep(1)
        }
        return true
      }
      cnt += 1
    }
    log.warn("writePtr update failed")
    false
  }

  def pop(): Option[T] = {
    var cnt = 0
    while (cnt < 5) {
      var expected = readPtr.get()
      if ((expected & BUSY) != 0) {
        NanoAkka.bufferPopBusy += 1
        log.warn("BUSY")
        return None
      }
      var desired = next(expected)
      if (expected == writePtr.get() % size) {
        return None
      }
      desired |= BUSY
      if (readPtr.weakCompareAndSet(expected, desired)) {
        expected = desired
        desired &= ~BUSY
        val t = array(desired)
        while (readPtr.weakCompareAndSet(expected, desired) == false) {
          log.warn("writePtr remove busy failed")
          NanoAkka.bufferCasRetries += 1
          Thread.sleep(1)
        }
        return Some(t)
      }
      cnt += 1
    }
    log.warn("writePtr update failed")
    None
  }

}


case class TimerMsg(id: Int)

case class TimerSource(thread: NanoThread, id: Int, interval: Int, repeat: Boolean)
  extends Source[TimerMsg] {
  thread.timerSources += this
  var expiresOn: Long = millis() + interval

  def timeout(): Long = expiresOn - millis()

  def millis(): Long = System.currentTimeMillis()

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


case class Sink[T]( size: Int = 3,var callback: T => Unit = NanoAkka.defaultHandler[T])(implicit ct: ClassTag[T]) extends Subscriber[T] with Invoker {
  val log: Logger = LoggerFactory.getLogger(classOf[NanoThread])
  var queue = new ArrayQueue[T](size)(ct)
  var thread: NanoThread = _
  def on(t: T): Unit = {
    if (thread == null) callback(t)
    else {
      queue.push(t)
      thread.enqueue(this)
    }
  }

  def request(): Unit = invoke()

  def invoke(): Unit = {
    val t = queue.pop()
    if (t.nonEmpty)
      callback(t.get)
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
      if ( timeout <0  && timerSource != null) timerSource.request()
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

  def >>[OUT](flow:Flow[T,OUT]) : Source[OUT] = {
    subscribe(flow)
    flow
  }

  def >>(f: T => Unit): Unit = subscribe(new SubscriberFunction[T](f))
}

class Source[OUT] extends Publisher[OUT] {
  val log: Logger = LoggerFactory.getLogger(classOf[NanoThread])

  var subscribers: ListBuffer[Subscriber[OUT]] = ListBuffer[Subscriber[OUT]]()

  def subscribe(subscriber: Subscriber[OUT]): Unit = {
    subscribers += subscriber
  }

  def emit(out: OUT): Unit = {
    if (subscribers.size == 0) log.warn(" no subscribers for " + out.getClass.toString)
    else subscribers.foreach(sub => sub.on(out))
  }
}

class LambdaSource[T](lambda: () => T) extends Source[T] with Requestable {
  def request() = {
    emit(lambda())
  }
}

class ValueSource[T](var value: T) extends Source[T] {
  var pass = true

  def request = {
    emit(value)
  }

  def update(v: T): Unit = {
    value = v
    if (pass) emit(value)
  }

  def apply(): T = value
}

abstract class Flow[IN, OUT] extends Source[OUT] with Subscriber[IN] {
  def ==(flow:Flow[OUT,IN]):Unit = {
    subscribe(flow)
    flow.subscribe(this)
  }
}

object QueueFlow

case class QueueFlow[T](val depth: Int)(implicit ct: ClassTag[T])
  extends Flow[T, T] with Invoker {
  //  val log: Logger = LoggerFactory.getLogger(classOf[QueueFlow[T]])

  var queue = new ArrayQueue[T](depth)(ct)
  var thread: NanoThread = _

  def on(t: T): Unit = {
    if (thread == null) {
      emit(t)
    }
    else {
      if (queue.push(t))
        thread.enqueue(this)
    }
  }

  def request(): Unit = invoke()

  def invoke(): Unit = {
    val t = queue.pop()
    if (t.nonEmpty) emit(t.get)
  }

  def async(thread: NanoThread): Unit = {
    this.thread = thread
  }

  def sync(): Unit = {
    thread = null
  }
}

object Flow

class ValueFlow[T] extends Flow[T, T] {
  var pass = false
  private var t: T = _

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



