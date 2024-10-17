package hkmc2
package codegen

import mlscript.utils.*, shorthands.*
import utils.*

import hkmc2.Message.MessageContext

import hkmc2.{semantics => sem}
import hkmc2.semantics.{Term => st}

import syntax.{Literal, Tree}
import semantics.*
import semantics.Term.*


abstract class Ret extends (Result => Block)
object Ret extends Ret:
  def apply(r: Result): Block = Return(r, implct = false)
object ImplctRet extends Ret:
  def apply(r: Result): Block = Return(r, implct = true)


class Subst(initMap: Map[Local, Value]):
  val map = initMap
  def +(kv: (Local, Value)): Subst =
    kv match
    case (ns: NamedSymbol, Value.Ref(ts: TempSymbol)) =>
      ts.nameHints += ns.name
    case _ =>
    Subst(map + kv)
  def apply(v: Value): Value = v match
    case Value.Ref(l) => map.getOrElse(l, v)
    case _ => v
object Subst:
  val empty = Subst(Map.empty)
  def subst(using sub: Subst): Subst = sub
end Subst

import Subst.subst


class Lowering(using TL, Raise, Elaborator.State):
  
  def returnedTerm(t: st)(using Subst): Block = term(t)(Ret)
  
  def term(t: st)(k: Result => Block)(using Subst): Block = t match
    case st.Lit(lit) => 
      k(Value.Lit(lit))
    case st.Ret(res) =>
      returnedTerm(res)
    case st.Ref(sym) =>
      sym match
      case sym: Local =>
        k(subst(Value.Ref(sym)))
    case st.App(f, arg) =>
      arg match
      case Tup(fs) =>
        val as = fs.map:
          case sem.Fld(sem.FldFlags.empty, value, N) => value
          case sem.Fld(flags, value, asc) =>
            TODO("Other argument forms")
        val l = new TempSymbol(summon[Elaborator.State].nextUid, S(t))
        subTerm(f): fr =>
          def rec(as: Ls[st], asr: Ls[Path]): Block = as match
            case Nil => k(Call(fr, asr.reverse))
            case a :: as =>
              subTerm(a): ar =>
                rec(as, ar :: asr)
          rec(as, Nil)
      case _ =>
        TODO("Other argument list forms")
    
    case st.Blk(Nil, res) => term(res)(k)
    case st.Blk((p @ (_: Ref | _: Lit)) :: stats, res) =>
      raise(WarningReport(msg"Pure expression in statement position" -> p.toLoc :: Nil))
      subTerm(st.Blk(stats, res))(k)
    case st.Blk((t: sem.Term) :: stats, res) =>
      subTerm(t)(r => term(st.Blk(stats, res))(k))
    case st.Blk((d: Declaration) :: stats, res) =>
      d match
      case td: TermDefinition =>
        td.body match
        case N => // abstract declarations have no lowering
          term(st.Blk(stats, res))(k)
        case S(bod) =>
          Define(TermDefn(td.k, td.sym, td.params, returnedTerm(bod)),
            term(st.Blk(stats, res))(k))
      case _ =>
        // TODO handle
        term(st.Blk(stats, res))(k)
    case st.Blk((LetDecl(sym)) :: stats, res) =>
      term(st.Blk(stats, res))(k)
    case st.Blk((DefineVar(sym, rhs)) :: stats, res) =>
      subTerm(rhs): r =>
        Assign(sym, r, term(st.Blk(stats, res))(k))
    case st.Blk(s :: stats, res) =>
      TODO(s)
      
    case st.Lam(params, body) =>
      k(Value.Lam(params, returnedTerm(body)))
    case t @ st.If(Split.Let(sym, trm, tl)) =>
      term(st.Blk(semantics.LetDecl(sym) :: semantics.DefineVar(sym, trm) :: Nil, st.If(tl)(t.normalized)))(k)
    case st.If(Split.Cons(
      Branch(scrut, Pattern.LitPat(tru @ Tree.BoolLit(true)), Split.Else(thn)),
      restSplit
    )) =>
      
      val elseBranch = restSplit match
        case Split.Else(els) => S(els)
        case Split.Nil => N
      
      elseBranch match
      case S(els) if k.isInstanceOf[Ret] =>
        subTerm(scrut): sr =>
          // Match(sr, Case.Lit(tru) -> term(thn)(k) :: Nil,
          //   Some(term(els)(k)), 
          //   Unreachable
          // )
          Match(sr, Case.Lit(tru) -> term(thn)(k) :: Nil,
            N, 
            term(els)(k)
          )
      case _ =>
        val l = new TempSymbol(summon[Elaborator.State].nextUid, S(t))
        subTerm(scrut): sr =>
            Match(sr, Case.Lit(tru) -> subTerm(thn)(r => Assign(l, r, End())) :: Nil,
              elseBranch.map(els => subTerm(els)(r => Assign(l, r, End()))),
              k(Value.Ref(l))
            )
    
    case Sel(prefix, nme) =>
      subTerm(prefix): p =>
        k(Select(p, nme))
    
    case Error => End("error")
    
    // case _ =>
    //   subTerm(t)(k)
  
  def subTerm(t: st)(k: Path => Block)(using Subst): Block =
    term(t):
      case v: Value => k(v)
      case p: Path => k(p)
      case r =>
        val l = new TempSymbol(summon[Elaborator.State].nextUid, N)
        Assign(l, r, k(l |> Value.Ref.apply))
  
  
  // val resSym = new TermSymbol(N, Tree.Ident("$res"))
  // def topLevel(t: st): Block =
  //   subTerm(t)(r => codegen.Assign(resSym, r, codegen.End()))(using Subst.empty)
  
  def topLevel(t: st): Block =
    term(t)(ImplctRet)(using Subst.empty)


