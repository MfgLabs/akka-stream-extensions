# Akka Stream Extensions

We are proud to opensource `Akka-Stream-Extensions` extending the very promising [Typesafe Akka-Stream](http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0-RC1/scala.html?_ga=1.42749861.1204922152.1421451776).

The main purpose of this project is to:

1. Develop generic Akka-Stream `Sources`/`Flows`/`Sinks` not provided out-of-the-box by Akka-Stream.

2. Make those structures very well tested & production ready.

3. Study/evaluate bleeding-edge concepts based on Akka-Stream & other technologies.

We have been developing this library in the context of [MfgLabs](http://mfglabs.com) for our production projects after identifying a few primitive structures that were common to many use-cases, not provided by Akka-Stream out of the box and not so easy to implement in a robust way.

## How-To

> Scaladoc is available [there](http://mfglabs.github.io/akka-stream-extensions/api/#package)


#### Add resolvers to your `build.sbt`

```scala
resolvers += Resolver.bintrayRepo("mfglabs", "maven")
```

#### Add dependencies to your `build.sbt`

> Currently depends on `akka-stream-1.0-M5`

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions" % "0.7"

// Postgres extensions
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-postgres" % "0.7"

// Elasticsearch extensions
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-elasticsearch" % "0.7"

// ...
```

<br/>
#### Sample

```scala
import com.mfglabs.stream._

// Source from a paginated REST Api
SourceExt
  .bulkPullerAsync(0L) { (currentPosition, downstreamDemand) =>
    val futResult: Future[Seq[Page]] = WSService.get(offset = currentPosition, nbPages = downstreamDemand)
    futResult
  }
  .via(FlowExt.rateLimiter(200 millis))
  .via(FlowExt.mapAsyncWithBoundedConcurrency(maxConcurrency = 8)(asyncTransform))

// Source from a Java InputStream
SourceExt
  .fromStream(inputStream)(ExecutionContextForBlockingOps(someEc))
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

> Many more helpers, check the [Scaladoc](http://mfglabs.github.io/akka-stream-extensions/api/#package) !


<br/>
<br/>
## Postgres extension

> This extension provides tools to stream data from/to Postgres

#### Dependencies

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-postgres" % "0.7"
```

<br/>
#### Sample

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

<br/>
## Elasticsearch extension

#### Dependencies

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-elasticsearch" % "0.7"
```

#### Sample

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

<br/>
<br/>
## Testing

To test postgres-extensions, you need to have Docker installed and running on your computer (the tests will automatically launch a docker container with a Postgres db).

<br/>
<br/>
## Tributes

We thank [MfgLabs](http://mfglabs.com) for sponsoring the development and the opensourcing of this library.

We believed it could be very useful & interesting to many people and we are sure some will help us debug & build more useful structures.

> So don't hesitate to [contribute](/akka-stream-extensions/contributing/)

<br/>
<br/>
## License

>This software is licensed under the Apache 2 license, quoted below.
>
>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
>
>(http://www.apache.org/licenses/LICENSE-2.0)[http://www.apache.org/licenses/LICENSE-2.0]
>
>Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

