import scala.util.Try
import scala.scalajs.js.annotation.JSExportTopLevel
import org.scalajs.dom
import org.scalajs.dom.document
import org.scalajs.dom.raw.{Event, TextEvent, UIEvent, HTMLTextAreaElement}
import mlscript.utils._
import mlscript.utils.shorthands._

object Main {
  def main(args: Array[String]): Unit = {
    val source = document.querySelector("#mlscript-input")
    update(source.textContent)
    source.addEventListener("input", typecheck)
  }
  @JSExportTopLevel("typecheck")
  def typecheck(e: UIEvent): Unit = {
    e.target match {
      case elt: HTMLTextAreaElement =>
        update(elt.value)
    }
  }
  def update(str: String): Unit = {
    // println(s"Input: $str")
    val target = document.querySelector("#mlscript-output")
    target.innerHTML = Try {
      import fastparse._
      import fastparse.Parsed.{Success, Failure}
      import mlscript.{MLParser, TypeError, Origin}
      val lines = str.splitSane('\n').toIndexedSeq
      val processedBlock = MLParser.addTopLevelSeparators(lines).mkString
      val fph = new mlscript.FastParseHelpers(str, lines)
      val parser = new MLParser(Origin("<input>", 0, fph))
      parse(processedBlock, parser.pgrm(_), verboseFailures = false) match {
        case f: Failure =>
          val Failure(err, index, extra) = f
          val (lineNum, lineStr, _) = fph.getLineColAt(index)
          "Parse error: " + extra.trace().msg +
            s" at line $lineNum:<BLOCKQUOTE>$lineStr</BLOCKQUOTE>"
        case Success(pgrm, index) =>
          println(s"Parsed: $pgrm")
          val typer = new mlscript.Typer(dbg = false, verbose = false, explainErrors = false)
          import typer._
          import mlscript._
          
          val res = new collection.mutable.StringBuilder
          val stopAtFirstError = true
          
          def getType(ty: typer.TypeScheme): Type = {
            val wty = ty.instantiate(0).widenVar
            println(s"Typed as: $wty")
            println(s" where: ${wty.showBounds}")
            val com = typer.compactType(wty)
            println(s"Compact type before simplification: ${com}")
            val sim = typer.simplifyType(com)
            println(s"Compact type after simplification: ${sim}")
            val exp = typer.expandCompactType(sim)
            exp
          }
          def formatError(culprit: Str, err: TypeError): Str = {
            val ws = "&nbsp;" * _
            val lf = "<br/>"
            val stackTrace = err.getStackTrace.map { case x => 
              s"""${ws(2)}at <span style="font-weight:bold">${x.getClassName}.${x.getMethodName}</span>
                  <span style="white-space:nowrap"$lf${ws(4)} in file <font color="gray">${x.getFileName}:${x.getLineNumber}</font></span>"""
            }.mkString("<br/>")
            s"""<b><font color="Red">
                Error in <font color="LightGreen">${culprit}</font>: ${err.mainMsg}
                ${err.allMsgs.tail.map(_._1.show.toString + "<br/>").mkString("&nbsp;&nbsp;&nbsp;&nbsp;")}
                </font></b>
                $stackTrace$htmlLineBreak"""
          }
          def formatBinding(nme: Str, ty: TypeScheme): Str = {
            val exp = getType(ty)
            s"""<b>
              <font color="#93a1a1">val </font>
              <font color="LightGreen">${nme}</font>: 
              <font color="LightBlue">${exp.show}</font>
              </b><br/>"""
          }

          var totalTypeErrors = 0
          var totalWarnings = 0
          var outputMarker = ""
          val showRelativeLineNums = false

          def report(diag: Diagnostic): Str = {
            var sb = new collection.mutable.StringBuilder
            def output(s: Str): Unit = {
              sb ++= outputMarker
              sb ++= s
              sb ++= htmlLineBreak
              ()
            }
            val sctx = Message.mkCtx(diag.allMsgs.iterator.map(_._1), "?")
            val headStr = diag match {
              case TypeError(msg, loco) =>
                totalTypeErrors += 1
                s"╔══ <strong style=\"color: #E74C3C\">[ERROR]</strong> "
              case Warning(msg, loco) =>
                totalWarnings += 1
                s"╔══ <strong style=\"color: #F39C12\">[WARNING]</strong> "
            }
            val lastMsgNum = diag.allMsgs.size - 1
            var globalLineNum = 0
            diag.allMsgs.zipWithIndex.foreach { case ((msg, loco), msgNum) =>
              val isLast = msgNum =:= lastMsgNum
              val msgStr = msg.showIn(sctx)
              if (msgNum =:= 0)
                output(headStr + msgStr)
              else
                output(s"${if (isLast && loco.isEmpty) "╙──" else "╟──"} ${msgStr}")
              if (loco.isEmpty && diag.allMsgs.size =:= 1) output("╙──")
              loco.foreach { loc =>
                val (startLineNum, startLineStr, startLineCol) =
                  loc.origin.fph.getLineColAt(loc.spanStart)
                if (globalLineNum =:= 0) globalLineNum += startLineNum - 1
                val (endLineNum, endLineStr, endLineCol) =
                  loc.origin.fph.getLineColAt(loc.spanEnd)
                var l = startLineNum
                var c = startLineCol // c starts from 1
                while (l <= endLineNum) {
                  val globalLineNum = loc.origin.startLineNum + l
                  val relativeLineNum = globalLineNum + 1
                  val shownLineNum =
                    if (showRelativeLineNums && relativeLineNum > 0)
                      s"l.+$relativeLineNum"
                    else "l." + globalLineNum
                  val prepre = "║  "
                  val pre = s"$shownLineNum: " // Looks like l.\d+
                  val curLine = loc.origin.fph.lines(l - 1)
                  val lastCol =
                    if (l =:= endLineNum) endLineCol else curLine.length + 1
                  val front = curLine.slice(0, c - 1)
                  val middle = underline(curLine.slice(c - 1, lastCol - 1))
                  val back = curLine.slice(lastCol - 1, curLine.size)
                  output(s"$prepre$pre\t$front$middle$back")
                  if (isLast && l =:= endLineNum) output("╙──")
                  c = 1
                  l += 1
                }
              }
            }
            if (diag.allMsgs.isEmpty) output("╙──")
            sb.toString
          }
          
          implicit val raise: Raise = throw _
          implicit var ctx: Ctx = try processTypeDefs(pgrm.typeDefs)(Ctx.init, raise) catch {
            case err: TypeError =>
              res ++= formatError("class definitions", err)
              Ctx.init
          }
          
          var decls = pgrm.otherTops
          while (decls.nonEmpty) {
            val d = decls.head
            decls = decls.tail
            try d match {
              case d @ Def(isrec, nme, L(rhs)) =>
                val errProv = TypeProvenance(rhs.toLoc, "def definition")
                val ty = typeLetRhs(isrec, nme, rhs)
                ctx += nme -> ty
                println(s"Typed `${d.nme}` as: $ty")
                println(s" where: ${ty.instantiate(0).showBounds}")
                res ++= formatBinding(d.nme, ty)
              case d @ Def(isrec, nme, R(rhs)) =>
                val errProv = TypeProvenance(rhs.toLoc, "def signature")
                ??? // TODO
              case s: Statement =>
                val errProv = TypeProvenance(s.toLoc, "expression in statement position")
                val (diags, desug) = s.desugared
                // report(diags) // TODO!!
                typer.typeStatement(desug, allowPure = true) match {
                  case R(binds) =>
                    binds.foreach {
                      case (nme, pty) =>
                        ctx += nme -> pty
                        res ++= formatBinding(nme, pty)
                    }
                  case L(pty) =>
                    val exp = getType(pty)
                    if (exp =/= Primitive("unit")) {
                      val nme = "res"
                      ctx += nme -> pty
                      res ++= formatBinding(nme, pty)
                    }
                }
            } catch {
              case err: TypeError =>
                if (stopAtFirstError) decls = Nil
                val culprit = d match {
                  case Def(isrec, nme, rhs) => "def " + nme
                  case LetS(isrec, Var(nme), rhs) => "let " + nme
                  case _: Statement => "statement"
                }
                res ++= report(err)
            }
          }
          res.toString
      }
    }.fold(err => s"""
      <font color="Red">
      Unexpected error: ${err}${
        err.printStackTrace
        err
      }</font>""", identity)
  }

  private val htmlLineBreak = "<br />"

  private def underline(fragment: Str): Str =
      s"<u style=\"text-decoration: #E74C3C dashed underline\">$fragment</u>"
}
