import com.typesafe.sbt.pgp.PgpKeys.publishSigned
import com.typesafe.sbt.SbtSite.SiteKeys._
import com.typesafe.sbt.SbtGhPages.GhPagesKeys._
import sbtrelease.ReleaseStep
import sbtrelease.ReleasePlugin.ReleaseKeys.releaseProcess
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities._
import sbtunidoc.Plugin.UnidocKeys._

val Akka            = "2.4.18"
val Elasticsearch   = "2.4.5"
val Jna             = "4.4.0"
val Postgresql      = "42.1.1"
val Shapeless       = "2.3.2"
val Scala           = "2.12.2"
val ScalaTest       = "3.0.3"

organization in ThisBuild := "com.mfglabs"

name in ThisBuild := "akka-stream-extensions"

scalaVersion in ThisBuild := Scala

crossScalaVersions in ThisBuild := Seq("2.11.11", Scala)

publishMavenStyle in ThisBuild := true

bintrayReleaseOnPublish in ThisBuild := false

scalacOptions in ThisBuild ++= Seq("-feature", "-unchecked", "-language:postfixOps")

resolvers in ThisBuild ++= Seq(
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
  Resolver.sonatypeRepo("releases"),
  DefaultMavenRepository
)

lazy val commonSettings = Seq(
  scmInfo := Some(ScmInfo(url("https://github.com/MfgLabs/akka-stream-extensions"),
    "git@github.com:MfgLabs/akka-stream-extensions.git")),
  libraryDependencies += "org.scalatest" %% "scalatest" % ScalaTest % Test
)

lazy val publishSettings = Seq(
  homepage := Some(url("https://github.com/MfgLabs/akka-stream-extensions")),
  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  autoAPIMappings := true,
  apiURL := Some(url("https://MfgLabs.github.io/akka-stream-extensions/api/")),
  publishMavenStyle := true,
  publishArtifact in packageDoc := false,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false }
)

lazy val noPublishSettings = Seq(
  publish := (),
  publishLocal := (),
  publishArtifact := false
)

lazy val docSettings = Seq(
  autoAPIMappings := true,
  unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(commons, postgres, elasticsearch, shapeless),
  site.addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), "api"),
  ghpagesNoJekyll := false,
  siteMappings ++= Seq(
    file("CONTRIBUTING.md") -> "contributing.md",
    file("README.md") -> "_includes/README.md"
  ),
  scalacOptions in (ScalaUnidoc, unidoc) ++= Seq(
    "-doc-source-url", scmInfo.value.get.browseUrl + "/tree/master€{FILE_PATH}.scala",
    "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath
  ),
  git.remoteRepo := "git@github.com:MfgLabs/akka-stream-extensions.git",
  includeFilter in makeSite := "*.html" || "*.css" || "*.png" || "*.jpg" || "*.gif" || "*.js" || "*.swf" || "*.yml" || "*.md" || "*.svg" || "*.eot" || "*.ttf" || "*.woff" || "*.woff2"
)

lazy val all = project.in(file("."))
  .aggregate(commons, shapeless, postgres, elasticsearch, docs)
  .settings(
    name := "commons-all",
    noPublishSettings
  )
  .dependsOn(commons, shapeless, postgres, elasticsearch, docs)

lazy val docs = project
  .settings(moduleName := "akka-stream-ext-docs")
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(unidocSettings)
  .settings(site.settings)
  .settings(ghpages.settings)
  .settings(docSettings)
  .dependsOn(commons, postgres, shapeless, elasticsearch)
  .enablePlugins(TutPlugin)

site.jekyllSupport()

lazy val commons = project.in(file("commons"))
  .settings(
    name := "akka-stream-extensions",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream" % Akka
    ),
    commonSettings,
    publishSettings
  )

lazy val postgres = project.in(file("extensions/postgres"))
  .dependsOn(commons)
  .settings(
    name := "akka-stream-extensions-postgres",
    resolvers += Resolver.bintrayRepo("softprops", "maven"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream"  % Akka,
      "org.postgresql"    % "postgresql"    % Postgresql % Provided
    ),
    commonSettings,
    publishSettings,
    fork := true,
    parallelExecution in Test := false
  )

lazy val elasticsearch = project.in(file("extensions/elasticsearch"))
  .dependsOn(commons)
  .settings(
    name := "akka-stream-extensions-elasticsearch",
    libraryDependencies ++= Seq(
      "com.typesafe.akka"   %% "akka-stream"         % Akka,
      "org.elasticsearch"   % "elasticsearch"        % Elasticsearch   % Provided,
      "net.java.dev.jna"    % "jna"                  % Jna             % Test
    ),
    commonSettings,
    publishSettings
  )

lazy val shapeless = project.in(file("extensions/shapeless"))
 .dependsOn(commons)
 .settings(
   name := "akka-stream-extensions-shapeless",
   libraryDependencies ++= Seq(
     "com.typesafe.akka" %% "akka-stream" % Akka,
     "com.chuusai"       %% "shapeless"   % Shapeless
   ),
   commonSettings,
   publishSettings
 )
