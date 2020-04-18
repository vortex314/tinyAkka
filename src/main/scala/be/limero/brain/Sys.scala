package be.limero.brain

class Sys {

}

object Sys {
  private val startTime :Long = System.currentTimeMillis()
  def millis:Long=System.currentTimeMillis()-startTime
}
