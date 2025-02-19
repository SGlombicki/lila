package lila.opening

import chess.format.{ FEN, Forsyth }
import chess.opening.FullOpeningDB
import chess.variant.Standard
import chess.{ Situation, Speed }
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import lila.common.LilaOpeningFamily

case class OpeningQuery(pgn: Vector[String], position: Situation, config: OpeningConfig) {
  def variant           = chess.variant.Standard
  val fen               = Forsyth >> position
  val opening           = FullOpeningDB findByFen fen
  val openingIfShortest = opening filter Opening.isShortest
  val family            = opening.map(_.family)
  def pgnString         = pgn mkString " "
  val name              = opening.fold(pgnString)(_.name)
  val key               = openingIfShortest.fold(pgn mkString "_")(_.key)
  def initial           = pgn.isEmpty
  def prev              = pgn.init.nonEmpty ?? OpeningQuery(pgn.init mkString " ", config)

  override def toString = s"$pgn $opening"
}

object OpeningQuery {

  def apply(q: String, config: OpeningConfig): Option[OpeningQuery] =
    byOpening(q, config) orElse fromPgn(q.replace("_", " "), config)

  private def byOpening(key: String, config: OpeningConfig) =
    Opening.shortestLines.get(key).map(_.pgn) flatMap { fromPgn(_, config) }

  private def fromPgn(pgn: String, config: OpeningConfig) = for {
    parsed <- chess.format.pgn.Reader.full(pgn).toOption
    replay <- parsed.valid.toOption
    game = replay.state
    sit  = game.situation
    if sit playable true
  } yield OpeningQuery(game.pgnMoves, sit, config)

  val firstYear  = 2016
  val firstMonth = s"$firstYear-01"
  def lastMonth =
    DateTimeFormat forPattern "yyyy-MM" print {
      val now = DateTime.now
      if (now.dayOfMonth.get > 7) now else now.minusMonths(1)
    }
}
