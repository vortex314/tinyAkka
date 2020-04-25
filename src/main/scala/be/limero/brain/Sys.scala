package be.limero.brain

object Sys {
  private val startTime :Long = System.currentTimeMillis()
  def millis:Long=System.currentTimeMillis()-startTime
}
