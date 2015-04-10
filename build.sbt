organization in ThisBuild := "com.mfglabs"

name in ThisBuild := "akka-stream-extensions"

version in ThisBuild := "0.5.1-SNAPSHOT"

scalaVersion in ThisBuild := "2.11.5"

publishTo in ThisBuild := Some("MFGLabs Snapshots" at "s3://mfg-mvn-repo/snapshots")

publishMavenStyle in ThisBuild := true

scalacOptions in ThisBuild ++= Seq("-feature", "-deprecation", "-unchecked", "-language:postfixOps")

resolvers in ThisBuild ++= Seq(
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
  Resolver.sonatypeRepo("releases"),
  DefaultMavenRepository
)

lazy val all = project.in(file("."))
  .aggregate(commons, postgres, shapeless)
  .settings(
    name := "commons-all",
    publishArtifact := false
  )

lazy val commons = project.in(file("commons"))
  .settings(
    name := "akka-stream-extensions",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-M4",
      "org.scalatest" %% "scalatest" % "2.1.6"
    )
  )

lazy val postgres = project.in(file("extensions/postgres"))
  .dependsOn(commons)
  .settings(
    name := "akka-stream-extensions-postgres",
    libraryDependencies ++= Seq(
      "org.postgresql" % "postgresql"  % "9.3-1102-jdbc4"
    )
  )

lazy val shapeless = project.in(file("extensions/shapeless"))
  .dependsOn(commons)
  .settings(
    name := "akka-stream-extensions-shapeless",
    libraryDependencies ++= Seq(
      "com.chuusai"       %% "shapeless"   % "2.1.0"
    )
  )
