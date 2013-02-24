package macroimpl

import language.experimental.macros
import scala.reflect.macros.Context


@LongName("MyUnit") class TranslateF

abstract class UnitName(val long: String)

//case class LongName(name: String) extends scala.annotation.StaticAnnotation

object CreateUnitMacros {

  def createImpl(c: Context)(long: c.Expr[String], short: c.Expr[String]): c.Tree = {
    import c.universe._
    import Helpers.packageName

    def extractString(e: c.Expr[String]) = e match {
      case Expr(Literal(Constant(s))) => s.toString
      case _ => c.abort(c.enclosingPosition, "unit name has to be a constant string")
    }

    val Template(_, _, existingCode) = c.enclosingTemplate

    val longName = extractString(long)
    val shortName = extractString(short)


    val className = newTypeName(s"Translate$$$shortName")
    println(className)
    val unitLookup = q"@macroimpl.LongName($longName) class $className extends macroimpl.UnitName($longName)"
    println(showRaw(unitLookup))
    c.introduceTopLevel(packageName, unitLookup)

    Template(Nil, emptyValDef, existingCode )
  }

  type MyUnit(long: String, short: String) = macro createImpl
}