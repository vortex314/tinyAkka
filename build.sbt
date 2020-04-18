name := "tinyAkka"

version := "0.1"

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-api" % "1.7.25",
  "ch.qos.logback" % "logback-classic" % "1.2.3"
)
// https://mvnrepository.com/artifact/org.eclipse.paho/org.eclipse.paho.client.mqttv3
libraryDependencies += "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % "1.2.3"
// https://mvnrepository.com/artifact/org.json4s/json4s-native
libraryDependencies += "org.json4s" %% "json4s-native" % "3.7.0-M2"
