package com.mfglabs.stream
package extensions.shapeless

import akka.NotUsed
import shapeless._
import shapeless.ops.hlist._
import shapeless.ops.nat._
import shapeless.ops.coproduct.Inject

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.event.Logging

import com.mfglabs.stream.FlowExt


object ShapelessStream extends ShapelessStream

trait ShapelessStream {

  /**
   * Builds at compile-time a fully typed-controlled flow that transforms a HList of Flows to a Flow from the Coproduct of inputs to the Coproduct of outputs.
   *
   * Flow[A1, B1] :: FLow[A2, B2] :: ... :: Flow[An, Bn] :: HNil => Flow[A1 :+: A2 :+: ... :+: An :+: CNil, B1 :+: B2 :+: ... +: Bn :+: CNil]
   *
   *
   *
   *                                             +-------- Flow[A1, B1] ---------+
   *                                             |                               |
   *                                             |-------- Flow[A2, B2] ---------|
   *                                             |                               |
   *   --> A1 :+: A2 :+: ... :+: An :+: CNil ----+-------- ............ ---------+-------> B1 :+: B2 :+: ... :+: Bn :+: CNil
   *                                             |                               |
   *                                             |                               |
   *                                             +-------- Flow[An, Bn] ---------+
   * 
   *
   * The flows is built with a custom FlexiRoute with `DemandFromAll` condition & FlexiMerge using `ReadAny` condition.
   *
   * Be careful, the order is NOT guaranteed due to the nature of used FlexiRoute & FlexiMerge and potentially to the flow you provide in your HList.
   * 
   * @param a Hlist of flows Flow[A1, B1] :: FLow[A2, B2] :: ... :: Flow[An, Bn] :: HNil
   * @return the flow of the Coproduct of inputs and the Coproduct of outputs Flow[A1 :+: A2 :+: ... :+: An :+: CNil, B1 :+: B2 :+: ... +: Bn :+: CNil, Unit]
   */
  def coproductFlow[HL <: HList, CIn <: Coproduct, COut <: Coproduct, CInOutlets <: HList, COutInlets <: HList](
    flows: HL
  )(
    implicit
      flowTypes: FlowTypes.Aux[HL, CIn, COut],
      obuild: OutletBuilder.Aux[CIn, CInOutlets],
      ibuild: InletBuilder.Aux[COut, COut, COutInlets],
      otrav: ToTraversable.Aux[CInOutlets, List, Outlet[_]],
      itrav: ToTraversable.Aux[COutInlets, List, Inlet[COut]],
      selOutletValue: SelectOutletValue.Aux[CIn, CInOutlets],
      flowBuilder: FlowBuilderC.Aux[CIn, COut, CInOutlets, HL, COutInlets]
  ): Graph[FlowShape[CIn, COut], NotUsed] =
    GraphDSL.create() { implicit builder =>
    
      import GraphDSL.Implicits._

      val router = builder.add(new CoproductFlexiRoute[CIn, CInOutlets]())
      val merger = builder.add(new CoproductFlexiMerge[COut, COutInlets]())

      flowBuilder.build(router.outs, flows, merger.ins)
      FlowShape(router.in, merger.out)
    }

  /**
   * Builds at compile-time a flow that transforms a HList of Flows to a Flow from the Coproduct of inputs to the Coproduct of outputs.
   *
   * Flow[A1, B1] :: FLow[A2, B2] :: ... :: Flow[An, Bn] :: HNil => Any
   *
   *
   *
   *                                             +-------- Flow[A1, B1] ---------+
   *                                             |                               |
   *                                             |-------- Flow[A2, B2] ---------|
   *                                             |                               |
   *   --> A1 :+: A2 :+: ... :+: An :+: CNil ----+-------- ............ ---------+-------> Any
   *                                             |                               |
   *                                             |                               |
   *                                             +-------- Flow[An, Bn] ---------+
   *
   *
   * The flows is built with a custom FlexiRoute with `DemandFromAll` condition & Merge using `ReadAny` condition.
   *
   * Be careful, the order is NOT guaranteed due to the nature of used FlexiRoute & FlexiMerge and potentially to the flow you provide in your HList.
   *
   * @param a Hlist of flows Flow[A1, B1] :: FLow[A2, B2] :: ... :: Flow[An, Bn] :: HNil
   * @return the flow of the Coproduct of inputs and the Coproduct of outputs Flow[A1 :+: A2 :+: ... :+: An :+: CNil, Any, Unit]
   */
  def coproductFlowAny[HL <: HList, CIn <: Coproduct, COut <: Coproduct, CInOutlets <: HList, Size <: Nat, N <: Nat](
    flows: HL
  )(
    implicit
      flowTypes: FlowTypes.Aux[HL, CIn, COut],
      build: OutletBuilder.Aux[CIn, CInOutlets],
      trav: ToTraversable.Aux[CInOutlets, List, Outlet[_]],
      selOutletValue: SelectOutletValue.Aux[CIn, CInOutlets],
      length: shapeless.ops.coproduct.Length.Aux[CIn, Size],
      toIntN: ToInt[Size],
      flowBuilder: FlowBuilder.Aux[CIn, COut, CInOutlets, HL, N]
  ): Graph[FlowShape[CIn, Any], NotUsed] =
    GraphDSL.create() { implicit builder =>

      import GraphDSL.Implicits._

      val router = builder.add(new CoproductFlexiRoute[CIn, CInOutlets]())
      val merge = builder.add(Merge[Any](toIntN()))

      flowBuilder.build(router.outs, flows, merge)
      FlowShape(router.in, merge.out)
    }

}


/** The terrible Shapeless structures */

/** A universal graph outlet builder function */
trait OutletFunction {
  def apply[H]: Outlet[H]
}

/** A typed outlet builder using a universal outlet builder function that builds a HList of outlets from the types in a coproduct */
trait OutletBuilder[C <: Coproduct] {
  type Out <: HList

  def apply(f: OutletFunction): Out
}

object OutletBuilder {
  type Aux[C <: Coproduct, HL <: HList] = OutletBuilder[C] { type Out = HL }

  implicit val last: Aux[CNil, HNil] = new OutletBuilder[CNil] {
    type Out = HNil
    def apply(f: OutletFunction): HNil = HNil
  }

  implicit def head[H, T <: Coproduct, HT <: HList](
    implicit tl: OutletBuilder.Aux[T, HT]
  ): OutletBuilder.Aux[H :+: T, Outlet[H] :: HT] = new OutletBuilder[H :+: T] {
    type Out = Outlet[H] :: HT
    def apply(f: OutletFunction): Outlet[H] :: HT = f.apply[H] :: tl.apply(f)
  }


}


/** Takes a coproduct instance and searches in a HList of outlets for corresponding typed outlet
 *  and returns the outlet and the value
 */
trait SelectOutletValue[C <: Coproduct] {
  type Outlets <: HList
  def apply(c: C, outlets: Outlets): (Outlet[_], Any)
}

object SelectOutletValue{
  type Aux[C <: Coproduct, HL <: HList] = SelectOutletValue[C] { type Outlets = HL }

  implicit def last[H, HL <: HList](
    implicit sel0: Selector[HL, Outlet[H]]
  ): SelectOutletValue.Aux[H :+: CNil, HL] = new SelectOutletValue[H :+: CNil] {
    type Outlets = HL
    def apply(c: H :+: CNil, outlets: HL): (Outlet[_], Any) =
      c match {
        case Inl(h) => outlets.select[Outlet[H]] -> h
        case Inr(_) => throw new RuntimeException("impossible case")
      }
  }

  implicit def head[H, T <: Coproduct, HL <: HList](
    implicit
      sel: SelectOutletValue.Aux[T, HL],
      selO: Selector[HL, Outlet[H]]
  ): SelectOutletValue.Aux[H :+: T, HL] = new SelectOutletValue[H :+: T] {
    type Outlets = HL
    def apply(c: H :+: T, outlets: HL): (Outlet[_], Any) =
      c match {
        case Inl(h) => outlets.select[Outlet[H]] -> h
        case Inr(t) => sel.apply(t, outlets)
      }
  }


}

/** The custom FanOutShape used by CoproductFlexiRoute to build typed outlets from types in the Coproduct */
class CoproductFanOutShape[C <: Coproduct, HL <: HList](
  val builder: OutletBuilder.Aux[C, HL],
  _init: FanOutShape.Init[C] = FanOutShape.Name[C]("CoproductFanOutShape")
) extends FanOutShape[C](_init) {
  self =>
  val rnd = new scala.util.Random
  val outs = builder.apply(new OutletFunction{
    def apply[H] = self.newOutlet[H](rnd.alphanumeric.take(5).mkString)
  })

  protected override def construct(i: FanOutShape.Init[C]) = new CoproductFanOutShape(builder, i)
}

/** The custom CoproductFlexiRoute that dispatches instance of the Coproduct to the right route according to the type using a DemandFromAll condition */
class CoproductFlexiRoute[C <: Coproduct, HL <: HList](implicit
  builder: OutletBuilder.Aux[C, HL],
  trav: ToTraversable.Aux[HL, List, Outlet[_]],
  sel: SelectOutletValue.Aux[C, HL]
) extends GraphStage[CoproductFanOutShape[C, HL]] {

  override val shape: CoproductFanOutShape[C, HL] = new CoproductFanOutShape(builder)
  val in:Inlet[C] = shape.in
  val out:HL = shape.outs

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    import scala.language.existentials

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val elm = grab(in)
        val (outlet, h) = sel.apply(elm, out)
        push(outlet.as[Any], h)
      }
    })

    out.toList[Outlet[_]](trav).foreach{ o =>
      setHandler(o, new OutHandler {
        override def onPull(): Unit = {
            if(isAvailable(in)){
              val (outlet, h) = sel.apply(grab(in), out)
              push(outlet.as[Any], h)
            } else if(!hasBeenPulled(in)) pull(in)
        }
      })
    }
  }
}

/** Universal Inlet builder function */
trait InletFunction {
  def apply[H]: Inlet[H]
}

/** Typed inlet builder that builds a HList of Inlets from the types in a coproduct using a universal builder function */
trait InletBuilder[C <: Coproduct] {
  type Sub <: Coproduct
  type Out <: HList

  def apply(f: InletFunction): Out
}

object InletBuilder {
  type Aux[C <: Coproduct, Sub0 <: Coproduct, HL <: HList] = InletBuilder[C] { type Sub = Sub0; type Out = HL }
  
  implicit def last[Sub0 <: Coproduct]: Aux[CNil, Sub0, HNil] = new InletBuilder[CNil] {
    type Sub = Sub0
    type Out = HNil
    def apply(f: InletFunction): HNil = HNil
  }
  
  implicit def head[H, T <: Coproduct, Sub0 <: Coproduct, HT <: HList](
    implicit tl: InletBuilder.Aux[T, Sub0, HT]
  ): InletBuilder.Aux[H :+: T, Sub0, Inlet[Sub0] :: HT] = new InletBuilder[H :+: T] {
    type Sub = Sub0
    type Out = Inlet[Sub0] :: HT
    def apply(f: InletFunction): Inlet[Sub0] :: HT = f.apply[Sub0] :: tl.apply(f)
  }


}

/** The custom FanInShape used by CoproductFlexiMerge to build typed inlets from types in the Coproduct */
class CoproductFanInShape[C <: Coproduct, HL <: HList](
  val builder: InletBuilder.Aux[C, C, HL],
  _init: FanInShape.Init[C] = FanInShape.Name[C]("CoproductFanInShape")
) extends FanInShape[C](_init) {
  self =>

  private val rnd = new scala.util.Random
  val ins: HL = builder.apply(new InletFunction{
    def apply[H] = self.newInlet[H](rnd.nextString(5))
  })

  protected override def construct(i: FanInShape.Init[C]) = new CoproductFanInShape(builder, i)
}


/** The custom CoproductFlexiMerge that merges elements received on all inlets to output Coproduct using ReadAny condition */
class CoproductFlexiMerge[C <: Coproduct, HL <: HList](implicit
  builder: InletBuilder.Aux[C, C, HL],
  trav: ToTraversable.Aux[HL, List, Inlet[C]]
) extends GraphStage[CoproductFanInShape[C, HL]]{

  override val shape: CoproductFanInShape[C, HL] = new CoproductFanInShape[C, HL](builder)
  val in = shape.ins
  val out = shape.out

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    in.toList[Inlet[C]](trav).foreach { i =>
      setHandler(i, new InHandler {
        override def onPush(): Unit = {
          push(out, grab(i))
        }
      })
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        in.toList[Inlet[C]](trav).foreach { i =>
          if (!hasBeenPulled(i)) pull(i)
          else if(isAvailable(i)) push(out, grab(i))
        }
      }
    })
  }
}

/** Extracts from a HList of flows a Coproduct of Input types and a Coproduct of Output types */
trait FlowTypes[HL <: HList] {
  type CIn <: Coproduct
  type COut <: Coproduct
}

object FlowTypes {
  type Aux[HL <: HList, CI <: Coproduct, CO <: Coproduct] = FlowTypes[HL] { type CIn = CI; type COut = CO }

  implicit val last: FlowTypes.Aux[HNil, CNil, CNil] =
    new FlowTypes[HNil] {
      type CIn = CNil
      type COut = CNil
    }

  implicit def hl[A, B, HT <: HList, CInT <: Coproduct, COutT <: Coproduct](
    implicit tl: FlowTypes.Aux[HT, CInT, COutT]
  ): FlowTypes.Aux[Flow[A, B, NotUsed] :: HT, A :+: CInT, B :+: COutT] =
    new FlowTypes[Flow[A, B, NotUsed] :: HT] {
      type CIn = A :+: CInT
      type COut = B :+: COutT
    }

}

/** Builds a Flow[CIn, Any] using provided FlowGraph.Builder */
trait FlowBuilder[CIn <: Coproduct, COut <: Coproduct] {
  type HLO <: HList
  type HLF <: HList
  type N <: Nat

  def build(outlets: HLO, flows: HLF, merge: UniformFanInShape[Any, Any])
           (implicit builder: GraphDSL.Builder[NotUsed]): Unit
}

object FlowBuilder{
  type Aux[CIn <: Coproduct, COut <: Coproduct, HLO0 <: HList, HLF0 <: HList, N0 <: Nat] =
    FlowBuilder[CIn, COut] { type HLO = HLO0; type HLF = HLF0; type N = N0 }

  implicit def last[HI, HO, HLO0 <: HList, HLF0 <: HList](
    implicit
      selO: Selector[HLO0, Outlet[HI]],
      selF: Selector[HLF0, Flow[HI, HO, NotUsed]],
      toInt: ToInt[Nat._1]
  ): FlowBuilder.Aux[HI :+: CNil, HO :+: CNil, HLO0, HLF0, Nat._1] =
    new FlowBuilder[HI :+: CNil, HO :+: CNil] {
      type HLO = HLO0
      type HLF = HLF0
      type N = Nat._1

      def build(outlets: HLO0, flows: HLF0, merge: UniformFanInShape[Any, Any])(implicit builder: GraphDSL.Builder[NotUsed]): Unit = {
        import GraphDSL.Implicits._
        val outlet = outlets.select[Outlet[HI]]
        val flow = flows.select[Flow[HI, HO, NotUsed]]
        outlet ~> flow ~> merge.in(merge.inlets.size - 1)
      }
    }

  implicit def head[HI, TI <: Coproduct, HO, TO <: Coproduct, HLO0 <: HList, HLF0 <: HList, N0 <: Nat](
    implicit
      selO: Selector[HLO0, Outlet[HI]],
      selF: Selector[HLF0, Flow[HI, HO, NotUsed]],
      flowBuilder: FlowBuilder.Aux[TI, TO, HLO0, HLF0, N0],
      toInt: ToInt[Succ[N0]]
  ): FlowBuilder.Aux[HI :+: TI, HO :+: TO, HLO0, HLF0, Succ[N0]] =
    new FlowBuilder[HI :+: TI, HO :+: TO] {
      type HLO = HLO0
      type HLF = HLF0
      type N = Succ[N0]

      def build(outlets: HLO0, flows: HLF0, merge: UniformFanInShape[Any, Any])(implicit builder: GraphDSL.Builder[NotUsed]): Unit = {
        import GraphDSL.Implicits._
        
        val outlet = outlets.select[Outlet[HI]]
        val flow = flows.select[Flow[HI, HO, NotUsed]]
        outlet ~> flow ~> merge.in(merge.inlets.size - toInt())

        flowBuilder.build(outlets, flows, merge)
      }
    }

}

/** Builds a Flow[CIn, COut] using provided FlowGraph.Builder */
trait FlowBuilderC[CIn <: Coproduct, COut <: Coproduct] {
  type HLO <: HList
  type HLF <: HList
  type HLI <: HList

  def build(outlets: HLO, flows: HLF, inlets: HLI)
           (implicit builder: GraphDSL.Builder[NotUsed]): Unit
}

object FlowBuilderC{
  type Aux[CIn <: Coproduct, COut <: Coproduct, HLO0 <: HList, HLF0 <: HList, HLI0 <: HList] =
    FlowBuilderC[CIn, COut] { type HLO = HLO0; type HLF = HLF0; type HLI = HLI0 }

  implicit def last[HI, HO, C <: Coproduct](
    implicit inj: Inject[C, HO]
  ): FlowBuilderC.Aux[
    HI :+: CNil, HO :+: CNil, Outlet[HI] :: HNil, Flow[HI, HO, NotUsed] :: HNil, Inlet[C] :: HNil
  ] =
    new FlowBuilderC[HI :+: CNil, HO :+: CNil] {
      type HLO = Outlet[HI] :: HNil
      type HLF = Flow[HI, HO, NotUsed] :: HNil
      type HLI = Inlet[C] :: HNil

      def build(outlets: Outlet[HI] :: HNil, flows: Flow[HI, HO, NotUsed] :: HNil, inlets: Inlet[C] :: HNil)(implicit builder: GraphDSL.Builder[NotUsed]): Unit = {
        import GraphDSL.Implicits._
        val outlet = outlets.head
        val inlet = inlets.head
        val flow = flows.head
        outlet ~> flow.map((e:HO) => Coproduct[C](e)) ~> inlet
      }
    }

  implicit def head[HI, TI <: Coproduct, HO, TO <: Coproduct, HLO0 <: HList, HLF0 <: HList, HLI0 <: HList, C <: Coproduct](
    implicit
      flowBuilder: FlowBuilderC.Aux[TI, TO, HLO0, HLF0, HLI0],
      inj: Inject[C, HO]
  ): FlowBuilderC.Aux[
    HI :+: TI, HO :+: TO, Outlet[HI] :: HLO0, Flow[HI, HO, NotUsed] :: HLF0, Inlet[C] :: HLI0
  ] = new FlowBuilderC[HI :+: TI, HO :+: TO] {
      type HLO = Outlet[HI] :: HLO0
      type HLF = Flow[HI, HO, NotUsed] :: HLF0
      type HLI = Inlet[C] :: HLI0

      def build(outlets: Outlet[HI] :: HLO0, flows: Flow[HI, HO, NotUsed] :: HLF0, inlets: Inlet[C] :: HLI0)(implicit builder: GraphDSL.Builder[NotUsed]): Unit = {
        import GraphDSL.Implicits._
        
        val outlet = outlets.head
        val flow = flows.head
        val inlet = inlets.head
        outlet ~> flow.map((e:HO) => Coproduct[C](e)) ~> inlet

        flowBuilder.build(outlets.tail, flows.tail, inlets.tail)
      }
    }

}

