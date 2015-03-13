package com.mfglabs.stream
package extensions.shapeless

import shapeless._
import shapeless.ops.hlist._
import shapeless.ops.nat._

import akka.stream.{FlowMaterializer, ActorFlowMaterializer, FanInShape, FanOutShape, Graph, UniformFanInShape, Outlet, Inlet}
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.event.Logging

import com.mfglabs.stream.FlowExt


trait OutletFunction {
  def apply[H]: Outlet[H]
}

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

trait InletFunction {
  def apply[H]: Inlet[H]
}

trait InletBuilder[C <: Coproduct] {
  type Out <: HList

  def apply(f: InletFunction): Out
}

object InletBuilder {
  type Aux[C <: Coproduct, HL <: HList] = InletBuilder[C] { type Out = HL }
  
  implicit val last: Aux[CNil, HNil] = new InletBuilder[CNil] {
    type Out = HNil
    def apply(f: InletFunction): HNil = HNil
  }
  
  implicit def head[H, T <: Coproduct, HT <: HList](
    implicit tl: InletBuilder.Aux[T, HT]
  ): InletBuilder.Aux[H :+: T, Inlet[H] :: HT] = new InletBuilder[H :+: T] {
    type Out = Inlet[H] :: HT
    def apply(f: InletFunction): Inlet[H] :: HT = f.apply[H] :: tl.apply(f)
  }


}



trait SelectOutletValue[C <: Coproduct] {
  type Outlets <: HList
  def apply(c: C, outlets: Outlets): (Outlet[_], Option[_])
}

object SelectOutletValue{
  type Aux[C <: Coproduct, HL <: HList] = SelectOutletValue[C] { type Outlets = HL }

  implicit def last[H, HL <: HList](
    implicit sel0: Selector[HL, Outlet[H]]
  ): SelectOutletValue.Aux[H :+: CNil, HL] = new SelectOutletValue[H :+: CNil] {
    type Outlets = HL
    def apply(c: H :+: CNil, outlets: HL): (Outlet[_], Option[_]) =
      c match {
        case Inl(h) => outlets.select[Outlet[H]] -> Some(h)
        case Inr(_) => throw new RuntimeException("toto")
      }
  }

  implicit def head[H, T <: Coproduct, HL <: HList](
    implicit
      sel: SelectOutletValue.Aux[T, HL],
      selO: Selector[HL, Outlet[H]]
  ): SelectOutletValue.Aux[H :+: T, HL] = new SelectOutletValue[H :+: T] {
    type Outlets = HL
    def apply(c: H :+: T, outlets: HL): (Outlet[_], Option[_]) = 
      c match {
        case Inl(h) => outlets.select[Outlet[H]] -> Some(h)
        case Inr(t) => sel.apply(t, outlets)
      }
  }


}


// trait SelectInletValue[C <: Coproduct] {
//   type Inlets <: HList
//   def apply(c: C, inlets: Inlets): (Inlet[C], Option[C])
// }

// object SelectInletValue{
//   type Aux[C <: Coproduct, HL <: HList] = SelectInletValue[C] { type Inlets = HL }

//   implicit def last[H, HL <: HList](
//     implicit sel0: Selector[HL, Inlet[H]]
//   ): SelectInletValue.Aux[H :+: CNil, HL] = new SelectInletValue[H :+: CNil] {
//     type Inlets = HL
//     def apply(c: H :+: CNil, inlets: HL): (Inlet[_], Option[_]) =
//       c match {
//         case Inl(h) => inlets.select[Inlet[H]] -> Some(h)
//         case Inr(_) => throw new RuntimeException("impossible case")
//       }
//   }

//   implicit def head[H, T <: Coproduct, HL <: HList](
//     implicit
//       sel: SelectInletValue.Aux[T, HL],
//       selO: Selector[HL, Inlet[H]]
//   ): SelectInletValue.Aux[H :+: T, HL] = new SelectInletValue[H :+: T] {
//     type Inlets = HL
//     def apply(c: H :+: T, inlets: HL): (Inlet[_], Option[_]) = 
//       c match {
//         case Inl(h) => inlets.select[Inlet[H]] -> Some(h)
//         case Inr(t) => sel.apply(t, inlets)
//       }
//   }

// }

class CoproductFanOutShape[C <: Coproduct, HL <: HList](
  val builder: OutletBuilder.Aux[C, HL],
  _init: FanOutShape.Init[C] = FanOutShape.Name[C]("CoproductFanOutShape")
) extends FanOutShape[C](_init) {
  self =>
  val rnd = new scala.util.Random
  // val outs = oo.toList[Outlet[_]](trav)
  val outs = builder.apply(new OutletFunction{
    def apply[H] = self.newOutlet[H](rnd.nextString(5))
  })

  protected override def construct(i: FanOutShape.Init[C]) = new CoproductFanOutShape(builder, i)
}

class CoproductFlexiRoute[C <: Coproduct, HL <: HList](implicit
  builder: OutletBuilder.Aux[C, HL],
  trav: ToTraversable.Aux[HL, List, Outlet[_]],
  sel: SelectOutletValue.Aux[C, HL]
) extends FlexiRoute[C, CoproductFanOutShape[C, HL]](
  new CoproductFanOutShape(builder), OperationAttributes.name("CoproductFanOutShape")
) {
  import FlexiRoute._

  override def createRouteLogic(p: PortT) = new RouteLogic[C] {

    override def initialState = State[Any](DemandFromAll(p.outs.toList[Outlet[_]](trav))) {
      (ctx, _, element) =>
        println("e="+element)
        val (outlet, Some(h)) = sel.apply(element, p.outs)
        println(s"outlet:$outlet h:$h")
        ctx.emit(outlet)(h)
        println(s"after")
        SameState
    }
 
    override def initialCompletionHandling = eagerClose
  }
}

class CoproductFanInShape[C <: Coproduct, HL <: HList](
  val builder: InletBuilder.Aux[C, HL],
  _init: FanInShape.Init[C] = FanInShape.Name("CoproductFanInShape")
) extends FanInShape[C](_init) {
  self =>

  val rnd = new scala.util.Random
  val ins = builder.apply(new InletFunction{
    def apply[H] = self.newInlet[H](rnd.nextString(5))
  })

  protected override def construct(i: FanInShape.Init[C]) = new CoproductFanInShape(builder, i)
}


class CoproductFlexiMerge[C <: Coproduct, HL <: HList](implicit
  builder: InletBuilder.Aux[C, HL],
  trav: ToTraversable.Aux[HL, List, Inlet[C]],
  sel: SelectInletValue.Aux[C, HL]
) extends FlexiRoute[C, CoproductFanInShape[C, HL]](
  new CoproductFanInShape(builder), OperationAttributes.name("CoproductFanInShape")
) {
  import FlexiMerge._

  override def createMergeLogic(p: PortT) = new MergeLogic[C] {

    override def initialState = State[C](ReadAny(p.ins.toList[Inlet[C]](trav))) {
      (ctx, _, element) =>
        println("e="+element)
        val (inlet, Some(h)) = sel.apply(element, p.ins)
        println(s"inlet:$inlet h:$h")
        ctx.emit(h)
        println(s"after")
        SameState
    }
 
    override def initialCompletionHandling = eagerClose
  }
}

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
  ): FlowTypes.Aux[Flow[A, B, Unit] :: HT, A :+: CInT, B :+: COutT] =
    new FlowTypes[Flow[A, B, Unit] :: HT] {
      type CIn = A :+: CInT
      type COut = B :+: COutT
    }

}

trait FlowBuilder[CIn <: Coproduct, COut <: Coproduct] {
  type HLO <: HList
  type HLF <: HList
  type N <: Nat

  def build(outlets: HLO, flows: HLF, merge: UniformFanInShape[Any, Any])
           (implicit builder: FlowGraph.Builder): Unit
}

object FlowBuilder{
  type Aux[CIn <: Coproduct, COut <: Coproduct, HLO0 <: HList, HLF0 <: HList, N0 <: Nat] =
    FlowBuilder[CIn, COut] { type HLO = HLO0; type HLF = HLF0; type N = N0 }

  implicit def last[HI, HO, HLO0 <: HList, HLF0 <: HList](
    implicit
      selO: Selector[HLO0, Outlet[HI]],
      selF: Selector[HLF0, Flow[HI, HO, Unit]],
      toInt: ToInt[Nat._1]
  ): FlowBuilder.Aux[HI :+: CNil, HO :+: CNil, HLO0, HLF0, Nat._1] =
    new FlowBuilder[HI :+: CNil, HO :+: CNil] {
      type HLO = HLO0
      type HLF = HLF0
      type N = Nat._1

      def build(outlets: HLO0, flows: HLF0, merge: UniformFanInShape[Any, Any])(implicit builder: FlowGraph.Builder): Unit = {
        import FlowGraph.Implicits._
        val outlet = outlets.select[Outlet[HI]]
        val flow = flows.select[Flow[HI, HO, Unit]]
        outlet ~> flow ~> merge.in(merge.inlets.size - 1)
      }
    }

  implicit def head[HI, TI <: Coproduct, HO, TO <: Coproduct, HLO0 <: HList, HLF0 <: HList, N0 <: Nat](
    implicit
      selO: Selector[HLO0, Outlet[HI]],
      selF: Selector[HLF0, Flow[HI, HO, Unit]],
      flowBuilder: FlowBuilder.Aux[TI, TO, HLO0, HLF0, N0],
      toInt: ToInt[Succ[N0]]
  ): FlowBuilder.Aux[HI :+: TI, HO :+: TO, HLO0, HLF0, Succ[N0]] =
    new FlowBuilder[HI :+: TI, HO :+: TO] {
      type HLO = HLO0
      type HLF = HLF0
      type N = Succ[N0]

      def build(outlets: HLO0, flows: HLF0, merge: UniformFanInShape[Any, Any])(implicit builder: FlowGraph.Builder): Unit = {
        import FlowGraph.Implicits._
        
        val outlet = outlets.select[Outlet[HI]]
        val flow = flows.select[Flow[HI, HO, Unit]]
        outlet ~> flow ~> merge.in(merge.inlets.size - toInt())

        flowBuilder.build(outlets, flows, merge)
      }
    }

}

object ShapelessStream {

  def coproductFlow[A, B, O1, O2](
    fa: Flow[A, O1, Unit], fb: Flow[B, O2, Unit]
  )(
    implicit
      builder: OutletBuilder.Aux[A :+: B :+: CNil, Outlet[A] :: Outlet[B] :: HNil],
      trav: ToTraversable.Aux[Outlet[A] :: Outlet[B] :: HNil, List, Outlet[_]],
      selOutletValue: SelectOutletValue.Aux[A :+: B :+: CNil, Outlet[A] :: Outlet[B] :: HNil]
  ): Flow[A :+: B :+: CNil, Any, Unit] =
    Flow() { implicit builder =>
    
      import FlowGraph.Implicits._

      val router = builder.add(new CoproductFlexiRoute[A :+: B :+: CNil, Outlet[A] :: Outlet[B] :: HNil])
      val merge = builder.add(Merge[Any](2))

      router.outs.select[Outlet[A]] ~> fa ~> merge.in(0)
      router.outs.select[Outlet[B]] ~> fb ~> merge.in(1)
      router.in -> merge.out
    }


  def coproductFlow[HL <: HList, CIn <: Coproduct, COut <: Coproduct, CInOutlets <: HList, Size <: Nat, N <: Nat](
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
  ): Flow[CIn, Any, Unit] =
    Flow() { implicit builder =>
    
      import FlowGraph.Implicits._

      val router = builder.add(new CoproductFlexiRoute[CIn, CInOutlets]())
      val merge = builder.add(Merge[Any](toIntN()))

      flowBuilder.build(router.outs, flows, merge)
      router.in -> merge.out
    }
}
