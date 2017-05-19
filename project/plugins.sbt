resolvers += Resolver.url(
  "tpolecat-sbt-plugin-releases",
    url("http://dl.bintray.com/content/tpolecat/sbt-plugin-releases"))(
        Resolver.ivyStylePatterns)

addSbtPlugin("com.frugalmechanic" % "fm-sbt-s3-resolver"     % "0.4.0")
addSbtPlugin("com.eed3si9n"       % "sbt-unidoc"             % "0.3.2")
addSbtPlugin("com.github.gseitz"  % "sbt-release"            % "0.7.1")
addSbtPlugin("com.jsuereth"       % "sbt-pgp"                % "1.0.0")
addSbtPlugin("com.typesafe.sbt"   % "sbt-ghpages"            % "0.5.3")
addSbtPlugin("com.typesafe.sbt"   % "sbt-site"               % "0.8.1")
addSbtPlugin("org.tpolecat"       % "tut-plugin"             % "0.5.0")
addSbtPlugin("me.lessis" % "bintray-sbt" % "0.3.0")