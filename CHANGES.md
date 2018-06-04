# MFG Akka-Stream-Extensions #

### Version 0.12.0 ###
* Upgrade to akka `2.5.12` (still depends on deprecated stuff).
* Deprecate `FlowExt.rechunkByteStringBySeparator` and `FlowExt.takeWhile`
* Remove deprecated `SourceExt.unfoldPullerAsync`

### Version 0.11.2 ###
* Add a `Debounce` graph which wrap elements in `Ok` or `Ko` depending on whether they are the first occurence in the given time window.
* Add `Flow.debounce` which keep only the fist occurence of an element in the given time window.

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
