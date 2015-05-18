import com.typesafe.sbt.pgp.PgpKeys.publishSigned
import com.typesafe.sbt.SbtSite.SiteKeys._
import com.typesafe.sbt.SbtGhPages.GhPagesKeys._
import sbtrelease.ReleaseStep
import sbtrelease.ReleasePlugin.ReleaseKeys.releaseProcess
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities._
import sbtunidoc.Plugin.UnidocKeys._

import bintray.Plugin._

organization in ThisBuild := "com.mfglabs"

name in ThisBuild := "akka-stream-extensions"

scalaVersion in ThisBuild := "2.11.6"

publishMavenStyle in ThisBuild := true

scalacOptions in ThisBuild ++= Seq("-feature", "-unchecked", "-language:postfixOps")

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
  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  autoAPIMappings := true,
  apiURL := Some(url("https://MfgLabs.github.io/akka-stream-extensions/api/")),
  publishMavenStyle := true,
  publishArtifact in packageDoc := false,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false }
) ++ bintrayPublishSettings

lazy val noPublishSettings = Seq(
  publish := (),
  publishLocal := (),
  publishArtifact := false
)

lazy val docSettings = Seq(
  autoAPIMappings := true,
  unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(commons),
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
  .settings(tutSettings)
  .settings(docSettings)
  .dependsOn(commons, postgres, shapeless, elasticsearch)

site.jekyllSupport()

lazy val commons = project.in(file("commons"))
  .settings(
    name := "akka-stream-extensions",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-RC2",
      "org.scalatest" %% "scalatest" % "2.1.6"
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
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-RC2",
      "org.postgresql" % "postgresql"  % "9.3-1102-jdbc4",
      "me.lessis" %% "tugboat" % "0.2.0" % "test"
    ),
    commonSettings,
    publishSettings
  )

lazy val elasticsearch = project.in(file("extensions/elasticsearch"))
  .dependsOn(commons)
  .settings(
    name := "akka-stream-extensions-elasticsearch",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-RC2",
      "org.elasticsearch" % "elasticsearch" % "1.3.2"
    ),
    commonSettings,
    publishSettings
  )

lazy val shapeless = project.in(file("extensions/shapeless"))
 .dependsOn(commons)
 .settings(
   name := "akka-stream-extensions-shapeless",
   libraryDependencies ++= Seq(
     "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-RC2",
     "com.chuusai"       %% "shapeless"                % "2.2.0-RC5"
   ),
   commonSettings,
   publishSettings
 )
