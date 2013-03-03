package macroimpl

import language.experimental.macros
import scala.reflect.macros.Context
import collection.mutable.ListBuffer
import scala.reflect.runtime.universe.{WeakTypeTag, TypeRef, TypeTag}

object Helpers {
  val packageName = "$$units$$"

  def simplify(units: Seq[GeneralUnit]): Seq[GeneralUnit] = {
    def reduceUnits(a: GeneralUnit, b: GeneralUnit) = SUnit(a.name, a.power + b.power)

    val unitGroups = units.groupBy(_.name).toList
    val unitList = for((_, units) <- unitGroups) yield units.reduce(reduceUnits)
    unitList.filter(_.power != 0).sortBy(_.name)
  }

  def combine(units: Seq[GeneralUnit]): GeneralUnit = {
    def makeTypes(next: GeneralUnit, sum: GeneralUnit) = CUnit(next, sum)
    val unitList = simplify(units)
    unitList match {
      case unit :: Nil => unit
      case xs => xs.reduceRight(makeTypes)
    }
  }

  def extractConstant[C <: Context, T](c: C)(s: c.universe.Tree): T = s match {
    case c.universe.Literal(c.universe.Constant(longName)) => longName match {
      case s: T => s
    }
  }

  def lookupShortUnit[C <: Context](c: C, short: String): String = {
    val unitSymbol = c.mirror.staticClass(packageName + ".Translate$" + short)
    val dummy = unitSymbol.typeSignature
    val annotations = unitSymbol.asClass.annotations
    annotations.find(a => a.tpe == c.universe.typeOf[LongName]) match {
      case None => c.abort(c.enclosingPosition, s"unknown unit '$short'")
      case Some(a) => extractConstant(c)(a.scalaArgs.head)
    }
  }

  def lookupLongUnit[C <: Context](c: C, long: String): String = {
    val unitSymbol = c.mirror.staticClass(packageName + ".Translate$" + long)
    val dummy = unitSymbol.typeSignature
    val annotations = unitSymbol.asClass.annotations
    annotations.find(a => a.tpe == c.universe.typeOf[ShortName]) match {
      case None => c.abort(c.enclosingPosition, s"unknown unit '$long'")
      case Some(a) => extractConstant(c)(a.scalaArgs.head)
    }
  }

  def lookupBaseUnit[C <: Context](c: C, unitName: String): Option[(String, Double)] = {
    val unitSymbol = c.mirror.staticClass(packageName + ".Translate$" + unitName)
    val dummy = unitSymbol.typeSignature
    val annotations = unitSymbol.asClass.annotations
    annotations.find(a => a.tpe == c.universe.typeOf[BaseUnit]).map {
      case a => (extractConstant(c)(a.scalaArgs.head), extractConstant(c)(a.scalaArgs.last))
    }
  }

  def parseType(c: Context)(tpe: c.Type) = TypeParser.parse(tpe.toString.replace("$", "").replace("macroimpl.", ""))

  def enforceUnitEquality(c: Context)(tpeA: c.Type, tpeB: c.Type) = {
    val typeA = parseType(c)(tpeA)
    val typeB = parseType(c)(tpeB)

    if(typeA != typeB)
      c.abort(c.enclosingPosition, s"type error, $typeA != $typeB")

    combine(typeA).toTree(c)
  }
}

import Helpers._


// to make it independent of number type, use Numeric typeclass from
// https://github.com/non/spire
// the one from scala is too slow
class Measure[T](val n: Int) extends AnyVal {
  override def toString = n.toString

  def +[U](that: Measure[U])(implicit tag: WeakTypeTag[T], tag2: WeakTypeTag[U]) =
    macro MeasureImpl.addition_impl[T, U]
  def -[U](that: Measure[U])(implicit tag: WeakTypeTag[T], tag2: WeakTypeTag[U]) =
    macro MeasureImpl.subtraction_impl[T, U]
  def *[U](that: Measure[U])(implicit tag: WeakTypeTag[T], tag2: WeakTypeTag[U]) =
    macro MeasureImpl.multiplication_impl[T, U]
  def /[U](that: Measure[U])(implicit tag: WeakTypeTag[T], tag2: WeakTypeTag[U]) =
    macro MeasureImpl.division_impl[T, U]

  def toInt = n
  def toLong = n.toLong
  def toDouble = n.toDouble
  def toFloat = n.toFloat

  def as(unitEx: String)(implicit tag: WeakTypeTag[T]) = macro MeasureImpl.as_impl[T]
  def unit(implicit tag: WeakTypeTag[T]) = macro MeasureImpl.get_unit_impl[T]
}



object MeasureImpl {
  def u(nEx: Int, unitEx: String) = macro u_impl

  def u(nEx: Double, unitEx: String) = macro u_double_impl

  type u(unitEx: String) = macro u_unit_impl

  def compute_unit(c: Context)(unitEx: c.Expr[String]) = {
    import c.universe._

    val unit = unitEx match {
      case Expr(Literal(Constant(s))) => s.toString
      case _ => c.abort(c.enclosingPosition, "unit has to be a constant string")
    }

    UnitParser[c.type](c).parse(unit)
  }

  def u_impl(c: Context)
        (nEx: c.Expr[Int], unitEx: c.Expr[String]): c.Expr[Any] = {
    import c.universe._
    val comp = new Precomputer[c.type](c)
    val nID = comp.compute(nEx.tree)

    val parsedUnit = compute_unit(c)(unitEx)

    c.Expr(Block(comp.evals.toList, q"new Measure[$parsedUnit]($nID)"))
  }

  def u_double_impl(c: Context)
        (nEx: c.Expr[Double], unitEx: c.Expr[String]): c.Expr[Any] = {
    import c.universe._
    val comp = new Precomputer[c.type](c)
    val nID = comp.compute(nEx.tree)

    val parsedUnit = compute_unit(c)(unitEx)

    c.Expr(Block(comp.evals.toList, q"new MeasureDouble[$parsedUnit]($nID)"))
  }

  def u_unit_impl(c: Context)(unitEx: c.Expr[String]): c.Tree = {
    val parsedUnit = compute_unit(c)(unitEx)
    import c.universe._
    AppliedTypeTree( Ident(newTypeName("Measure")), List( parsedUnit ) )
  }

  def as_impl[T: c.WeakTypeTag]
    (c: Context)(unitEx: c.Expr[String])(tag: c.Expr[WeakTypeTag[T]]): c.Expr[Any] = {
    val parsedUnit = compute_unit(c)(unitEx)
    import c.universe._

    val typeSource = parseType(c)(tag.actualType)
    val treeTarget = compute_unit(c)(unitEx)
    val typeTarget = TypeParser.parse(treeTarget.toString.replace("$", ""))

    // purge units that occur in both source and target
    var leftoverUnits: List[GeneralUnit] = List()
    var targetUnits = typeTarget.toList
    for(u <- typeSource) {
      targetUnits.find(_.name == u.name) match {
        case None => leftoverUnits ::= u
        case Some(v) =>
          targetUnits = targetUnits.filter(_.name != u.name)
          if(v.power - u.power != 0) {
            targetUnits ::= SUnit(v.name, v.power - u.power)
          }
      }
    }

    var conversionFactor = 1.0
    var targetUnitsBase = ListBuffer[GeneralUnit]()
    var sourceUnitsBase = ListBuffer[GeneralUnit]()

    def getBase(unitEx: String): (Seq[GeneralUnit], Double) = {
      val base: Seq[(Seq[GeneralUnit], Double)] = UnitParser(c).parseToUnitList(unitEx).map {
        case long => lookupBaseUnit(c, long.name) match {
          case None => (List(long), 1.0)
          case Some((shortBaseEx, newFactor)) =>
            val (units, factor) = getBase(shortBaseEx)
            (units.map(u => SUnit(u.name, u.power * long.power)), newFactor * factor)
        }
      }
      val factor = base.map(_._2).reduce(_ * _)
      val units = base.flatMap(_._1)
      (units, factor)
    }


    def processUnits(src: Seq[GeneralUnit], dst: ListBuffer[GeneralUnit], op: (Double, Double) => Double) = {
      for(u <- src) {
        val short = lookupLongUnit(c, u.name)
        val (baseUnits, factor) = getBase(short)
        baseUnits.foreach(v => dst += SUnit(v.name, u.power * v.power))
        conversionFactor = op(conversionFactor, factor)
      }
    }

    processUnits(targetUnits, targetUnitsBase, _ / _)
    processUnits(leftoverUnits, sourceUnitsBase, _ * _)

    if(sourceUnitsBase.toList.sortBy(_.name) != targetUnitsBase.toList.sortBy(_.name)) {
      c.abort(c.enclosingPosition, s"couldn't convert Measure - incompatible units: $sourceUnitsBase, $targetUnitsBase")
    }

    val comp = new Precomputer[c.type](c)
    val nID = comp.compute(c.prefix.tree)

    val stats = q"new Measure[$parsedUnit](($nID.n.toDouble * $conversionFactor).toInt)"

    c.Expr(Block(comp.evals.toList, stats))
  }

  def get_unit_impl[T: c.WeakTypeTag](c: Context)
    (tag: c.Expr[WeakTypeTag[T]]): c.Expr[String] = {

    def prettyUnit(u: GeneralUnit) = {
      val short = lookupLongUnit(c, u.name)
      if(u.power.abs == 1) short
      else s"$short^${Math.abs(u.power)}"
    }

    val typeA = parseType(c)(tag.actualType)
    val (posUnits, negUnits) = simplify(typeA).partition(u => u.power > 0)
    val posUnitsString = posUnits match {
      case Nil => "1"
      case l => l.map(prettyUnit).mkString("*")
    }
    val negUnitsString = negUnits match {
      case Nil => ""
      case l => " / " + negUnits.map(prettyUnit).mkString
    }
    val unit = posUnitsString + negUnitsString

    import c.universe._
    c.Expr[String](Literal(Constant(unit)))
  }

  def addition_impl[T: c.WeakTypeTag, U: c.WeakTypeTag]
    (c: Context)
    (that: c.Expr[Measure[U]])
    (tag: c.Expr[WeakTypeTag[T]], tag2: c.Expr[WeakTypeTag[U]]): c.Expr[Any] = {
    import c.universe._

    val resultType = enforceUnitEquality(c)(tag.actualType, that.actualType)
    val comp = new Precomputer[c.type](c)
    val (aID, bID) = (comp.compute(c.prefix.tree), comp.compute(that.tree))

    c.Expr(Block(comp.evals.toList, q"new Measure[$resultType]($aID.n + $bID.n)"))
  }

  def subtraction_impl[T: c.WeakTypeTag, U: c.WeakTypeTag]
    (c: Context)
    (that: c.Expr[Measure[U]])
    (tag: c.Expr[WeakTypeTag[T]], tag2: c.Expr[WeakTypeTag[U]]): c.Expr[Any] = {
    import c.universe._

    val resultType = enforceUnitEquality(c)(tag.actualType, that.actualType)
    val comp = new Precomputer[c.type](c)
    val (aID, bID) = (comp.compute(c.prefix.tree), comp.compute(that.tree))

    c.Expr(Block(comp.evals.toList, q"new Measure[$resultType]($aID.n - $bID.n)"))
  }

  def multiplication_impl[T: c.WeakTypeTag, U: c.WeakTypeTag]
    (c: Context)
    (that: c.Expr[Measure[U]])
    (tag: c.Expr[WeakTypeTag[T]], tag2: c.Expr[WeakTypeTag[U]]): c.Expr[Any] = {
    import c.universe._

    val typeA = parseType(c)(tag.actualType)
    val typeB = parseType(c)(that.actualType)

    val resultType = combine(typeA ++ typeB).toTree(c)

    val comp = new Precomputer[c.type](c)
    val (aID, bID) = (comp.compute(c.prefix.tree), comp.compute(that.tree))

    c.Expr(Block(comp.evals.toList, q"new Measure[$resultType]($aID.n * $bID.n)"))
  }

  def division_impl[T: c.WeakTypeTag, U: c.WeakTypeTag]
    (c: Context)
    (that: c.Expr[Measure[U]])
    (tag: c.Expr[WeakTypeTag[T]], tag2: c.Expr[WeakTypeTag[U]]): c.Expr[Any] = {
    import c.universe._

    val typeA = parseType(c)(tag.actualType)
    val typeB = parseType(c)(that.actualType)

    val resultType = combine(typeA ++ typeB.map(u => SUnit(u.name, -u.power))).toTree(c)

    val comp = new Precomputer[c.type](c)
    val (aID, bID) = (comp.compute(c.prefix.tree), comp.compute(that.tree))

    c.Expr(Block(comp.evals.toList, q"new Measure[$resultType]($aID.n / $bID.n)"))
  }
}
