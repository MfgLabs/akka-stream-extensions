## Akka Stream Extensions

We are proud to opensource `Akka-Stream-Extensions` extending [Typesafe Akka-Stream](http://doc.akka.io/docs/akka/2.4.18/scala/stream/).

The main purpose of this project is to:

1. Develop generic `Sources`/`Flows`/`Sinks` not provided out-of-the-box by Akka-Stream.

2. Make those structures very well tested & production ready.

3. Study/evaluate streaming concepts based on Akka-Stream & other technologies (AWS, Postgres, ElasticSearch, ...).

We have been developing this library in the context of [MFG Labs](http://mfglabs.com) for our production projects after identifying a few primitive structures that were common to many use-cases, not provided by Akka-Stream out of the box and not so easy to implement in a robust way.

## How-To

Scaladoc is available [there](http://mfglabs.github.io/akka-stream-extensions/api/current).


### Add resolvers to your `build.sbt`

```scala
resolvers += Resolver.bintrayRepo("mfglabs", "maven")
```

### Add dependencies to your `build.sbt`

Currently depends on `akka-stream-2.4.18`

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions" % "0.11.2"
```

Changelog [here](CHANGES.md)

### Sample

```scala
import com.mfglabs.stream._

// Source from a paginated REST Api
val pagesStream: Source[Page, ActorRef] = SourceExt
  .bulkPullerAsync(0L) { (currentPosition, downstreamDemand) =>
    val futResult: Future[Seq[Page]] = WSService.get(offset = currentPosition, nbPages = downstreamDemand)
    futResult.map {
      case Nil => Nil -> true // stop the stream if the REST Api delivers no more results
      case p   => p -> false
    }
  }

someBinaryStream
  .via(FlowExt.rechunkByteStringBySeparator(ByteString("\n"), maximumChunkBytes = 5 * 1024))
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
Many more helpers, check the [Scaladoc](http://mfglabs.github.io/akka-stream-extensions/api/current)!

## Postgres extension

This extension provides tools to stream data from/to Postgres.

### Dependencies

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-postgres" % "0.11.2"
```

### Prerequisites
#### Test only
Pull all docker images launched by the tests
``` bash
docker pull postgres:8.4
docker pull postgres:9.6
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
  .via(FlowExt.rechunkByteStringBySeparator(ByteString("\n"), maximumChunkBytes = 5 * 1024))

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
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-elasticsearch" % "0.11.2"
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
  .queryAsStream(
    QueryBuilders.matchAllQuery(),
    index = "index",
    `type` = "type",
    scrollKeepAlive = 1 minutes,
    scrollSize = 1000
  )
```

## Shapeless extension

This extension allows to build at compile-time a fully typed-controlled flow that transforms a HList of Flows to a Flow from the Coproduct of inputs to the Coproduct of outputs.

For more details on the history of this extension, read [this article](http://mandubian.com/2015/05/05/shapelessstream/).

### Dependencies

```scala
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions-shapeless" % "0.11.2"
```

### Sample

```scala
// 1 - Create a type alias for your coproduct
type C = Int :+: String :+: Boolean :+: CNil

// The sink to consume all output data
val sink = Sink.fold[Seq[C], C](Seq())(_ :+ _)

// 2 - a sample source wrapping incoming data in the Coproduct
val f = GraphDSL.create(sink) { implicit builder => sink =>
      import GraphDSL.Implicits._
      val s = Source.fromIterator(() => Seq(
        Coproduct[C](1),
        Coproduct[C]("foo"),
        Coproduct[C](2),
        Coproduct[C](false),
        Coproduct[C]("bar"),
        Coproduct[C](3),
        Coproduct[C](true)
      ).toIterator)

// 3 - our typed flows
      val flowInt = Flow[Int].map{i => println("i:"+i); i}
      val flowString = Flow[String].map{s => println("s:"+s); s}
      val flowBool = Flow[Boolean].map{s => println("s:"+s); s}

// >>>>>> THE IMPORTANT THING
// 4 - build the coproductFlow in a 1-liner
val fr = builder.add(ShapelessStream.coproductFlow(flowInt :: flowString :: flowBool :: HNil))
// <<<<<< THE IMPORTANT THING

// 5 - plug everything together using akkastream DSL
  s ~> fr.in
  fr.out ~> sink
  ClosedShape
}

// 6 - run it
RunnableGraph.fromGraph(f).run().futureValue.toSet should equal (Set(
  Coproduct[C](1),
  Coproduct[C]("foo"),
  Coproduct[C](2),
  Coproduct[C](false),
  Coproduct[C]("bar"),
  Coproduct[C](3),
  Coproduct[C](true)
))
```


## AWS

Check our project [MFG Labs/commons-aws](https://github.com/MfgLabs/commons-aws) also providing streaming extensions for Amazon S3 & SQS.

## Testing

To test postgres-extensions, you need to have Docker installed and running on your computer (the tests will automatically launch a docker container with a Postgres db).

## Tributes

[MFG Labs](http://mfglabs.com) sponsored the development and the opensourcing of this library.

We hope this library will be useful & interesting to a few ones and that some of you will help us debug & build more useful structures.

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

