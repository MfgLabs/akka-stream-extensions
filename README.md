## Akka Stream Extensions

We are proud to opensource `Akka-Stream-Extensions` extending the very promising [Typesafe Akka-Stream](http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0-RC2/scala.html).

The main purpose of this project is to:

1. Develop generic `Sources`/`Flows`/`Sinks` not provided out-of-the-box by Akka-Stream.

2. Make those structures very well tested & production ready.

3. Study/evaluate streaming concepts based on Akka-Stream & other technologies (AWS, Postgres, ElasticSearch, ...).

We have been developing this library in the context of [MFG Labs](http://mfglabs.com) for our production projects after identifying a few primitive structures that were common to many use-cases, not provided by Akka-Stream out of the box and not so easy to implement in a robust way.

## How-To

Scaladoc is available [there](http://mfglabs.github.io/akka-stream-extensions/api/#package).


### Add resolvers to your `build.sbt`

```scala
resolvers += Resolver.bintrayRepo("mfglabs", "maven")
```

### Add dependencies to your `build.sbt`

Currently depends on `akka-stream-1.0-RC2`

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions" % "0.7.1"
```

### Sample

```scala
import com.mfglabs.stream._

// Source from a paginated REST Api
val pagesStream: Source[Page, Unit] = SourceExt
  .bulkPullerAsync(0L) { (currentPosition, downstreamDemand) =>
    val futResult: Future[Seq[Page]] = WSService.get(offset = currentPosition, nbPages = downstreamDemand)
    futResult
  }

someBinaryStream
  .via(FlowExt.rechunkByteStringBySeparator(ByteString("\n"), maxChunkSize = 5 * 1024))
  .map(_.utf8String)
  .via(
    FlowExt.customStatefulProcessor(Vector.empty[String])( // grouping by 100 except when we encounter a "flush" line
      (acc, line) => {
        if (acc.length == 100) (None, acc)
        else if (line == "flush") (None, acc :+ line)
        else (Some(acc :+ line), Vector.empty)
      },
      lastPushIfUpstreamEnds = acc => acc
    )
  )
```
Many more helpers, check the [Scaladoc](http://mfglabs.github.io/akka-stream-extensions/api/#package)!

## Postgres extension

This extension provides tools to stream data from/to Postgres.

### Dependencies

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-postgres" % "0.7.1"
```

### Sample

```scala
import com.mfglabs.stream._
import com.mfglabs.stream.extensions.postgres._

implicit val pgConnection = PgStream.sqlConnAsPgConnUnsafe(sqlConnection)
implicit val blockingEc = ExecutionContextForBlockingOps(someEc)

PgStream
  .getQueryResultAsStream(
    "select a, b, c from table", 
    options = Map("FORMAT" -> "CSV")
  )
  .via(FlowExt.rechunkByteStringBySeparator(ByteString("\n"), maxChunkSize = 5 * 1024))

someLineStream
  .via(PgStream.insertStreamToTable(
    "schema", 
    "table", 
    options = Map("FORMAT" -> "CSV")
  ))
```

## Elasticsearch extension

### Dependencies

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-elasticsearch" % "0.7.1"
```

### Sample

```scala
import com.mfglabs.stream._
import com.mfglabs.stream.extensions.elasticsearch._
import org.elasticsearch.client.Client
import org.elasticsearch.index.query.QueryBuilders

implicit val blockingEc = ExecutionContextForBlockingOps(someEc)
implicit val esClient: Client = // ...

EsStream
  .queryAsAsStream(
    QueryBuilders.matchAllQuery(),
    index = "index",
    `type` = "type",
    scrollKeepAlive = 1 minutes,
    scrollSize = 1000
  )
```

## AWS

Check our project [MFG Labs/commons-aws](https://github.com/MfgLabs/commons-aws) also providing streaming extensions for Amazon S3 & SQS.

## Testing

To test postgres-extensions, you need to have Docker installed and running on your computer (the tests will automatically launch a docker container with a Postgres db).

## Tributes

We thank [MFG Labs](http://mfglabs.com) for sponsoring the development and the opensourcing of this library.

We believed it could be very useful & interesting to many people and we are sure some will help us debug & build more useful structures.

<div class="push">
  <p>So don't hesitate to contribute</p>
  
  <a href="{{ site.baseurl }}/contributing/" alt="go one contribute page" class="btn-round grey">
    <span class="ico arrow_g"></span>
  </a>
</div>

<div class="license">
  <h2>License</h2>
  
  <p>This software is licensed under the Apache 2 license, quoted below.</p>
  
  <p>
    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance 
    with the License. You may obtain a copy of the License <a href="http://www.apache.org/licenses/LICENSE-2.0" target="_blank" alt="go to apache.org">here</a>.
  </p>
  
  <p>
    Unless required by applicable law or agreed to in writing, software distributed under the License is distributed 
    on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for 
    the specific language governing permissions and limitations under the License.
  </p>
</div>

