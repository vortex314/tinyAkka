package be.limero.brain

import java.net.{Inet4Address, InetAddress}

object Sys {
  private val startTime :Long = System.currentTimeMillis()
  def millis:Long=System.currentTimeMillis()-startTime
  val ip :InetAddress= InetAddress.getLocalHost
  def hostname :String = ip.getHostName
}
