# Akka Stream Extensions
Library of useful Sources / Flows / Sinks for Akka Stream.

## Resolver:
```scala
resolvers ++= Seq(
  "MFG releases" at "s3://mfg-mvn-repo/releases",
  "MFG snapshots" at "s3://mfg-mvn-repo/snapshots"
)
```

## Dependencies
Currently depends on akka-stream-1.0-M4

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions" % "0.5-SNAPSHOT"

// Postgres extensions
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-postgres" % "0.5-SNAPSHOT"
```

## Use

Commons:

```scala
import com.mfglabs.stream._

val formattedSource: Source[String] = 
  SourceExt
    .fromFile(new File("path"))(ExecutionContextForBlockingOps(someEc))
    .via(FlowExt.rechunkByteStringBySeparator(ByteString("\n")))
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

val queryStream: Source[ByteString] = 
  PgStream
    .getQueryResultAsStream("select a, b, c from table")

val streamOfNbInsertedLines: Flow[ByteString, Long] = 
  someLineStream
    .via(PgStream.insertStreamToTable("schema", "table"))
```
