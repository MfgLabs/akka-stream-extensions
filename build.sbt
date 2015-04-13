organization in ThisBuild := "com.mfglabs"

name in ThisBuild := "akka-stream-extensions"

version in ThisBuild := "0.6.0-SNAPSHOT"

scalaVersion in ThisBuild := "2.11.5"

publishTo in ThisBuild := {
  val s3Repo = "s3://mfg-mvn-repo"
  if (isSnapshot.value)
    Some("snapshots" at s3Repo + "/snapshots")
  else
    Some("releases" at s3Repo + "/releases")
}

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
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-M5",
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
