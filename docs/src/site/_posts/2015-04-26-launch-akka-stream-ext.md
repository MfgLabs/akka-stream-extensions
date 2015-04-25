---
layout: post
title: Unleashing Akka Stream Extensions
---

> Opensource Scala Library of generic, useful & higher-level `Sources`/`Flows`/`Sinks` for [Akka-Stream](http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0-RC1/scala.html?_ga=1.42749861.1204922152.1421451776) sponsored by [MfgLabs](http://mfglabs.com).

## Resolver:
```scala
resolvers ++= Seq(
  "MFG releases" at "s3://mfg-mvn-repo/releases",
  "MFG snapshots" at "s3://mfg-mvn-repo/snapshots"
)
```

## Dependencies
Currently depends on akka-stream-1.0-M5

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions" % "0.6.1-SNAPSHOT"

// Postgres extensions
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-postgres" % "0.6.1-SNAPSHOT"

// Elasticsearch extensions
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-elasticsearch" % "0.6.1-SNAPSHOT"
```

## Use

Commons:

```scala
import com.mfglabs.stream._

val formattedSource: Source[String, Unit] = 
  SourceExt
    .fromFile(new File("path"))(ExecutionContextForBlockingOps(someEc))
    .via(FlowExt.rechunkByteStringBySeparator(ByteString("\n"), maxChunkSize = 5 * 1024))
    .map(_.utf8String)
    .via(FlowExt.zipWithIndex)
    .map { case (line, i) => s"Line $i: $line" }
```

Postgres extension:

```scala
import com.mfglabs.stream._
import com.mfglabs.stream.extensions.postgres._

implicit val pgConnection = PgStream.sqlConnAsPgConnUnsafe(sqlConnection)
implicit val blockingEc = ExecutionContextForBlockingOps(someEc)

val queryStream: Source[ByteString, Unit] = PgStream
    .getQueryResultAsStream(
        "select a, b, c from table", 
        options = Map("FORMAT" -> "CSV")
     )

val streamOfNbInsertedLines: Flow[ByteString, Long, Unit] = someLineStream
    .via(PgStream.insertStreamToTable(
        "schema", "table", 
        options = Map("FORMAT" -> "CSV")
     ))
```

Elasticsearch extension:

```scala
import com.mfglabs.stream._
import com.mfglabs.stream.extensions.elasticsearch._
import org.elasticsearch.client.Client
import org.elasticsearch.index.query.QueryBuilders

implicit val blockingEc = ExecutionContextForBlockingOps(someEc)
implicit val esClient: Client = // ...

val queryStream: Source[String, Unit] = EsStream
    .queryAsAsStream(
        QueryBuilders.matchAllQuery(),
        index = "index",
        `type` = "type",
        scrollKeepAlive = 1 minutes,
        scrollSize = 1000
    )
```