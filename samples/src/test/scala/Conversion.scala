package macros

import macroimpl._
import MeasureImpl.u
import units._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

class ConversionSpec extends FlatSpec with ShouldMatchers {
  "Converting a Measure" should "adjust it's value" in {
    u(10, "m*s").as("ft*s") should equal (u(32, "ft*s"))
    u(10, "km").as("m") should equal (u(10000, "m"))
    u(10, "ft*m").as("m*m") should equal (u(3, "m^2"))
    u(2, "min").as("s") should equal (u(120, "s"))

    u(10, "m/s").as("km/h") should equal (u(36, "km/h"))
  }

  it should "resolve units recursively" in {
    u(1, "h").as("s") should equal (u(3600, "s"))
    u(1, "h").as("s").unit should equal ("s")
  }

  it should "work with complex base units" in {
    u(1, "N").as("dyn") should equal (u(100000, "dyn"))
    u(1, "N").as("dyn").unit should equal ("dyn")
  }

  it should "take into account any offsets" in {
    u(0.0, "C").as("K").toDouble should be (273.15 plusOrMinus 0.001)
    u(273.15, "K").as("C").toDouble should be (0.0 plusOrMinus 0.001)

    u(20.0, "F").as("K").toDouble should be (266.483 plusOrMinus 0.001)
    u(266.483, "K").as("F").toDouble should be (20.0 plusOrMinus 0.001)

    u(-40.0, "C").as("F").toDouble should be (-40.0 plusOrMinus 0.001)
    u(-40.0, "F").as("C").toDouble should be (-40.0 plusOrMinus 0.001)
  }

  it should "work on various derived units" in {
    // ohm's law
    (u(10, "V") / u(5, "ohm")).as("A").unit should be ("A")
    (u(10, "V") / u(5, "ohm")).as("A").toInt should be (2)

    // power from resistance
    (u(5, "A") * u(5, "A") * u(2, "ohm")).as("W").unit should be ("W")
    (u(5, "A") * u(5, "A") * u(2, "ohm")).as("W").toInt should be (50)

    (u(5, "V") * u(15, "V") / u(3, "ohm")).as("W").unit should be ("W")
    (u(5, "V") * u(15, "V") / u(3, "ohm")).as("W").toInt should be (25)
  }

  it should "be able to convert between energy and matter :D" in {
    (u(1.0, "g") * u(2.998e8, "m/s") * u(2.998e8, "m/s")).as("J").toDouble should be (
      8.988e13 plusOrMinus 1e10)
  }

}
