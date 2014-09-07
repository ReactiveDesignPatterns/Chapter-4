name := "chapter-4"

organization := "com.reactivedesignpatterns"

version := "0.1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.3.6",
  "org.scala-lang.modules" %% "scala-async" % "0.9.2",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.6" % "test",
  "junit" % "junit" % "4.11" % "test",
  "org.scalatest" %% "scalatest" % "2.2.2" % "test"
)

scalaVersion := "2.11.2"

spray.boilerplate.BoilerplatePlugin.Boilerplate.settings

