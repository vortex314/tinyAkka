import sbtassembly.MergeStrategy
import Process._

import scala.sys.process.Process
//lazy val root = (project in file(".")).disablePlugins(JavaServerAppPackaging)
lazy val root = Project(id="project", base = file("."))

val deployAndRunTask = TaskKey[Unit]("deploy-and-run-task", "Short example")

deployAndRunTask := {
  def shellRun(command: String*) = Process(command.toSeq).!!.trim
  println(" rcp jar ")
  val result = shellRun("/usr/bin/rcp", "./target/scala-2.13/tinyAkka-assembly-0.1.jar", "pi3.local:tinyAkka.jar")
  println(result)
}
name := "tinyAkka"
version := "0.1"
scalaVersion := "2.13.2"

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-api" % "1.7.25",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % "1.2.3",
  "org.json4s" %% "json4s-native" % "3.7.0-M2"
)
// https://mvnrepository.com/artifact/org.eclipse.paho/org.eclipse.paho.client.mqttv3
// https://mvnrepository.com/artifact/org.json4s/json4s-native
// get credentials for artifactory
credentials += Credentials(Path(sys.env.getOrElse("SBT_CREDENTIALS_PATH", Path.userHome.toString)) / ".sbt" / ".credentials")
// don't download javadoc
transitiveClassifiers in Global := Seq(Artifact.SourceClassifier)
lazy val commonSettings = Seq(
  version := "0.1-SNAPSHOT",
  organization := "com.example",
  scalaVersion := "2.10.1",
  test in assembly := {},
  mainClass in assembly := Some("be.limero.brain.Main"),
  assemblyJarName in assembly := "utils.jar",
)


val defaultMergeStrategy: String => MergeStrategy = {
  case x if Assembly.isConfigFile(x) =>
    MergeStrategy.concat
  case PathList(ps @ _*) if Assembly.isReadme(ps.last) || Assembly.isLicenseFile(ps.last) =>
    MergeStrategy.rename
  case PathList("META-INF", xs @ _*) =>
    (xs map {_.toLowerCase}) match {
      case ("manifest.mf" :: Nil) | ("index.list" :: Nil) | ("dependencies" :: Nil) =>
        MergeStrategy.discard
      case ps @ (x :: xs) if ps.last.endsWith(".sf") || ps.last.endsWith(".dsa") =>
        MergeStrategy.discard
      case "plexus" :: xs =>
        MergeStrategy.discard
      case "services" :: xs =>
        MergeStrategy.filterDistinctLines
      case ("spring.schemas" :: Nil) | ("spring.handlers" :: Nil) =>
        MergeStrategy.filterDistinctLines
      case _ => MergeStrategy.deduplicate
    }
  case _ => MergeStrategy.deduplicate
}