# MFG Akka-Stream-Extensions #

### Version 0.11.1 ###
* Fix `tut-plugin` bug which added `tut-core` to the POM [#28](https://github.com/MfgLabs/akka-stream-extensions/pull/28).

### Version 0.11.0 ###
* Add cross compilation for scala `2.11.11` and `2.12.2` [#26](https://github.com/MfgLabs/akka-stream-extensions/pull/26)
* Upgrade dependencies
* Remove deprecated :
  * `SinkExt.collect` use `Sink.seq`
  * `SourceExt.fromStream(InputStream)` use `StreamConverters.fromInputStream`
* Deprecate `SourceExt.unfoldPullerAsync`, use `Source.unfoldAsync`
* Rewrite `EsStream.queryAsStream` using `Source.unfoldAsync`
* Switch some dependencie to `Provided` (you need to specify those, they will not be transitively pulled)
  * In `akka-stream-extensions-postgres` : `"org.postgresql" % "postgresql"`
  * In `akka-stream-extensions-elasticsearch` : `"org.elasticsearch" % "elasticsearch"`
