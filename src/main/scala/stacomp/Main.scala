package stacomp

import scala.collection.mutable

///////////////////////////
// SYSTEM SPECIFICATION //
/////////////////////////

enum TimeVal {
  case At(e: Expr)
  case Until(e: Expr)
  case Henceforth
}

sealed trait ArrayLike extends Expr {
  def apply(e: Expr): ArrayLike
  def size: ArraySizeExpr
  def visit(sb: StringBuilder, sspec: SystemSpec): Unit = {
      this match {
        case SubArray(dims, name, inds) => {
          if (dims == 0) { 
            sb ++= s"$name"
            inds.map(ind => {
                sb ++= "("
                ind.visit(sb, sspec)
                sb ++= ")"
            })
            return
          }
        }
      }
      throw new IllegalArgumentException
  }
}

case class InputArray(
    dims: Int,
    name: String
) extends ArrayLike {
  require(dims > 0)
  def size: ArraySizeExpr = {
    ArraySizeExpr(dims, name, List())
  }
  def apply(e: Expr): ArrayLike = {
    SubArray(dims - 1, name, List(e))    
  }
}

case class SubArray(
  dims: Int, 
  name: String,
  inds: List[Expr],
) extends ArrayLike {
  require(dims > 0 || inds.length > 0)
  def size: ArraySizeExpr = {
    ArraySizeExpr(dims, name, inds)
  }
  def apply(e: Expr): ArrayLike = {
    SubArray(dims - 1, name, inds :+ e)
  }
}

case class SysIndex(
    name: String
) extends Expr {
  def visit(sb: StringBuilder, sspec: SystemSpec): Unit = sb ++= name
}

case class SystemSpec(
    name: String,
    indices: List[SysIndex],
    arrays: List[InputArray]
) {
  require(this.indices.length > 0)
  require(this.arrays.length > 0)
  def genFnSignature(sb: StringBuilder, fnname: String) = {
    sb ++= s"def $fnname(t: BigInt)("
    sb ++= this.indices.map(ind => s"${ind.name}: BigInt").mkString(", ")
    sb ++= ")("
    sb ++= this.arrays.map(arr => s"${arr.name}: " + "List[".repeat(arr.dims) + "BigInt" + "]".repeat(arr.dims)).mkString(", ")
    sb ++= "): BigInt = {\n"
  }
}

/////////////////////////
// CELL SPECIFICATION //
///////////////////////

sealed trait CellOrSysPort extends Expr {
  def getName(): String
  override def visit(sb: StringBuilder, sspec: SystemSpec): Unit = {
    sb ++= s"${getName()}(t)("
    sb ++= sspec.indices.map(ind => ind.name).mkString(",")
    sb ++= ")("
    sb ++= sspec.arrays.map(arr => arr.name).mkString(",")
    sb ++= ")"
  }
}

case class CellPort(
    name: String
) extends CellOrSysPort {
  def getName(): String = name
}

case class CellSpec(
    inputPorts: List[CellOrSysPort],
    outputPorts: List[(CellPort, Expr)]
) {
  def compileOutputFns(sb: StringBuilder, sspec: SystemSpec): Unit = {
    for ((port, e) <- this.outputPorts) {
      sspec.genFnSignature(sb, port.name)
      e.visit(sb, sspec)
      sb ++= "\n}\n"
    }
  }
}

case class ConnSpec(
    connections: Map[CellPort, String]
) {
  def compileInputFns(sb: StringBuilder, sspec: SystemSpec): Unit = {
    // for ((port, ))
  }
}

//////////////////////////
// INPUT SPECIFICATION //
////////////////////////

type Schedule = List[(TimeVal, Expr)]
def Schedule(xs: (TimeVal, Expr)*) = List(xs: _*)

enum IndConstraint {
  case Lt(e1: Expr, e2: Expr)
  case Leq(e1: Expr, e2: Expr)
  case Eq(e1: Expr, e2: Expr)

  def visit(sb: StringBuilder, sspec: SystemSpec): Unit = {
    this match
      case Lt(e1, e2) => {
        e1.visit(sb, sspec)
        sb ++= "<"
        e2.visit(sb, sspec)
      }
      case Leq(e1, e2) => {
        e1.visit(sb, sspec)
        sb ++= "<="
        e2.visit(sb, sspec)
      }
      case Eq(e1, e2) => {
        e1.visit(sb, sspec)
        sb ++= "=="
        e2.visit(sb, sspec)
      }
  }
}

def compileSchedule(schedule: Schedule, sb: StringBuilder, sspec: SystemSpec): Unit = {
  if (schedule.length == 1) {
    schedule.head._2.visit(sb, sspec)
    return
  }
  for (((tv, exp), i) <- schedule.zipWithIndex) {
    val cond = if (i >= ((schedule.length) - 1)) {
      "else"
    } else if (i == 0) {
      "if"
    } else {
      "else if"
    }
    if (cond == "else") {
      sb ++= "else {\n"
      exp.visit(sb, sspec)
      sb ++= "\n} "
    } else {
      tv match {
        case TimeVal.At(e: Expr) => {
          sb ++= s"$cond (t == "
          e.visit(sb, sspec)
          sb ++= ") {\n"
          exp.visit(sb, sspec)
          sb ++= "\n} "
        }
        case TimeVal.Until(e: Expr) => {
          sb ++= s"$cond (t < "
          e.visit(sb, sspec)
          sb ++= ") {\n"
          exp.visit(sb, sspec)
          sb ++= "\n} "
        }
        case TimeVal.Henceforth => {
          sb ++= "else {\n"
          exp.visit(sb, sspec)
          sb ++= "\n} "
        }
      }
    }
  }
}

case class SysPort(
    name: String,
    // is string for now, might be smarter to make it an actual type later
    schedule: Schedule,
    indexConstraints: List[IndConstraint],
    defaultValue: Expr
) extends CellOrSysPort {
  require(schedule.length > 0)
  require(indexConstraints.length > 0)
  def getName(): String = name

  def compileFn(sb: StringBuilder, sspec: SystemSpec): Unit = {
    sspec.genFnSignature(sb, name)
    // TODO: generate requires
    sb ++= "if ("
    for ((constraint, i) <- indexConstraints.zipWithIndex) {
      sb ++= "!("
      constraint.visit(sb, sspec)
      sb ++= ")"
      if (i < ((indexConstraints.length) - 1)) {
        sb ++= " || "
      }
    }
    sb ++= ") { "
    defaultValue.visit(sb, sspec)
    sb ++= " } else {\n"
    compileSchedule(schedule, sb, sspec)
    sb ++= "\n}\n}\n"
  }
}

case class InputSpec(
    ports: List[SysPort]
) {
  def compileSysInputFns(sb: StringBuilder, sspec: SystemSpec): Unit = {
    ports.map(port => port.compileFn(sb, sspec))
  }
}

//////////////////////////////
// Putting it all together //
////////////////////////////

case class SystolicSpec(
    systemSpec: SystemSpec,
    inputSpec: InputSpec,
    cellSpec: CellSpec
    // connSpec: ConnSpec
) {
  def compileStainless(): String = {
    val sb = mutable.StringBuilder()

    sb ++= """import stainless.lang.*
import stainless.annotation.*
import stainless.collection.*
import stainless.proof.check"""

    sb ++= s"\n\nobject ${systemSpec.name} {\n"

    // Compile functions for output ports of cells
    cellSpec.compileOutputFns(sb, systemSpec)

    inputSpec.compileSysInputFns(sb, systemSpec)

    // connSpec.compileCellInputFns(sb, systemSpec)

    sb ++= s"}"

    sb.toString()
  }
}
