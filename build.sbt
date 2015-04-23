import com.typesafe.sbt.pgp.PgpKeys.publishSigned
import com.typesafe.sbt.SbtSite.SiteKeys._
import com.typesafe.sbt.SbtGhPages.GhPagesKeys._
import sbtrelease.ReleaseStep
import sbtrelease.ReleasePlugin.ReleaseKeys.releaseProcess
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities._
import sbtunidoc.Plugin.UnidocKeys._


organization in ThisBuild := "com.mfglabs"

name in ThisBuild := "akka-stream-extensions"

scalaVersion in ThisBuild := "2.11.6"

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

lazy val commonSettings = Seq(
  scmInfo := Some(ScmInfo(url("https://github.com/MfgLabs/akka-stream-extensions"),
    "git@github.com:MfgLabs/akka-stream-extensions.git"))
)

lazy val publishSettings = Seq(
  homepage := Some(url("https://github.com/MfgLabs/akka-stream-extensions")),
  licenses := Seq("APLv2" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  autoAPIMappings := true,
  apiURL := Some(url("https://MfgLabs.github.io/akka-stream-extensions/api/")),
  publishMavenStyle := true,
  publishArtifact in packageDoc := false,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  publishTo <<= version { (v: String) =>
    val nexus = "https://oss.sonatype.org/"

    if (v.trim.endsWith("SNAPSHOT"))
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  }/*,
  pomExtra := (
    <scm>
      <url>git@github.com:non/cats.git</url>
      <connection>scm:git:git@github.com:non/cats.git</connection>
    </scm>
    <developers>
      <developer>
        <id>non</id>
        <name>Erik Osheim</name>
        <url>http://github.com/non/</url>
      </developer>
    </developers>
  ),
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishSignedArtifacts,
    setNextVersion,
    commitNextVersion,
    pushChanges
  )*/
)

lazy val docSettings = Seq(
  autoAPIMappings := true,
  unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(commons),
  site.addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), "api"),
  // site.addMappingsToSiteDir(tut, "_tut"),
  // ghpagesNoJekyll := false,
  //siteMappings += file("CONTRIBUTING.md") -> "contributing.md",
  scalacOptions in (ScalaUnidoc, unidoc) ++= Seq(
    "-doc-source-url", scmInfo.value.get.browseUrl + "/tree/master€{FILE_PATH}.scala",
    "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath
  ),
  git.remoteRepo := "git@github.com:MfgLabs/akka-stream-extensions.git"/*,
  includeFilter in makeSite := "*.html" | "*.css" | "*.png" | "*.jpg" | "*.gif" | "*.js" | "*.swf" | "*.yml" | "*.md"*/
)

lazy val theSettings = commonSettings ++ publishSettings

lazy val all = project.in(file("."))
  .aggregate(commons, postgres, shapeless, elasticsearch, docs)
  .settings(
    name := "commons-all"
  )
  .settings(theSettings)
  .settings(noPublishSettings)
  .dependsOn(commons, postgres, shapeless, elasticsearch, docs)

lazy val docs = project
  .settings(moduleName := "akka-stream-ext-docs")
  .settings(theSettings)
  .settings(noPublishSettings)
  .settings(unidocSettings)
  .settings(site.settings)
  .settings(ghpages.settings)
  .settings(tutSettings)
  .settings(docSettings)
  .dependsOn(commons, postgres, shapeless, elasticsearch)

lazy val commons = project.in(file("commons"))
  .settings(
    name := "akka-stream-extensions",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-M5",
      "org.scalatest" %% "scalatest" % "2.1.6"
    )
  )
  .settings(theSettings)

lazy val postgres = project.in(file("extensions/postgres"))
  .dependsOn(commons)
  .settings(
    name := "akka-stream-extensions-postgres",
    libraryDependencies ++= Seq(
      "org.postgresql" % "postgresql"  % "9.3-1102-jdbc4"
    )
  )

lazy val elasticsearch = project.in(file("extensions/elasticsearch"))
  .dependsOn(commons)
  .settings(
    name := "akka-stream-extensions-elasticsearch",
    libraryDependencies ++= Seq(
      "org.elasticsearch" % "elasticsearch" % "1.3.2"
    )
  )

lazy val shapeless = project.in(file("extensions/shapeless"))
  .dependsOn(commons)
  .settings(
    name := "akka-stream-extensions-shapeless",
    libraryDependencies ++= Seq(
      "com.chuusai"       %% "shapeless"   % "2.2.0-RC4"
    )
  )

lazy val noPublishSettings = Seq(
  publish := (),
  publishLocal := (),
  publishArtifact := false
)
