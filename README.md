---
layout: page
title: QuickStart
---

# Akka Stream Extensions

Library of useful Sources / Flows / Sinks for Akka Stream.

## Resolver

```scala
resolvers += Resolver.bintrayRepo("mfglabs", "maven")
```

## Dependencies
Currently depends on akka-stream-1.0-M5

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions" % "0.7"

// Postgres extensions
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-postgres" % "0.7"

// Elasticsearch extensions
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-elasticsearch" % "0.7"

// ...
```

## Use

[Scaladoc](http://mfglabs.github.io/akka-stream-extensions/api/#package)

### Commons

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

// Many more helpers, check the Scala doc !
```

### Postgres extension

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

### Elasticsearch extension

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

## Testing

To test postgres-extensions, you need to have Docker installed and running on your computer (the tests will automatically 
launch a docker container with a Postgres db).