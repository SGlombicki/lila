package lila.insight

import play.api.i18n.Lang
import play.api.libs.json._
import scala.concurrent.ExecutionContext

import lila.common.LightUser

case class Chart(
    question: JsonQuestion,
    xAxis: Chart.Xaxis,
    valueYaxis: Chart.Yaxis,
    sizeYaxis: Chart.Yaxis,
    series: List[Chart.Serie],
    sizeSerie: Chart.Serie,
    games: List[JsObject]
)

object Chart {

  case class Xaxis(
      name: String,
      categories: List[JsValue],
      dataType: String
  )

  case class Yaxis(
      name: String,
      dataType: String
  )

  case class Serie(
      name: String,
      dataType: String,
      stack: Option[String],
      data: List[Double]
  )

  def fromAnswer[X](
      getLightUser: LightUser.Getter
  )(answer: Answer[X])(implicit lang: Lang, ec: ExecutionContext): Fu[Chart] = {

    import answer._, question._

    def xAxis(implicit lang: Lang) =
      Xaxis(
        name = dimension.name,
        categories = clusters.map(_.x).map(InsightDimension.valueJson(dimension)),
        dataType = InsightDimension dataTypeOf dimension
      )

    def sizeSerie =
      Serie(
        name = metric.per.tellNumber,
        dataType = InsightMetric.DataType.Count.name,
        stack = none,
        data = clusters.map(_.size.toDouble)
      )

    def series =
      clusters
        .foldLeft(Map.empty[String, Serie]) { case (acc, cluster) =>
          cluster.insight match {
            case Insight.Single(point) =>
              val key = metric.name
              acc.updated(
                key,
                acc.get(key) match {
                  case None =>
                    Serie(
                      name = metric.name,
                      dataType = metric.dataType.name,
                      stack = none,
                      data = List(point.y)
                    )
                  case Some(s) => s.copy(data = point.y :: s.data)
                }
              )
            case Insight.Stacked(points) =>
              points.foldLeft(acc) { case (acc, (metricValueName, point)) =>
                val key = s"${metric.name}/${metricValueName.name}"
                acc.updated(
                  key,
                  acc.get(key) match {
                    case None =>
                      Serie(
                        name = metricValueName.name,
                        dataType = metric.dataType.name,
                        stack = metric.name.some,
                        data = List(point.y)
                      )
                    case Some(s) => s.copy(data = point.y :: s.data)
                  }
                )
              }
          }
        }
        .map { case (_, serie) =>
          serie.copy(data = serie.data.reverse)
        }
        .toList

    def sortedSeries =
      answer.clusters.headOption.fold(series) {
        _.insight match {
          case Insight.Single(_)       => series
          case Insight.Stacked(points) => series.sortLike(points.map(_._1.name), _.name)
        }
      }

    def gameUserJson(player: lila.game.Player): Fu[JsObject] =
      (player.userId ?? getLightUser) map { lu =>
        Json
          .obj("rating" -> player.rating)
          .add("name", lu.map(_.name))
          .add("title", lu.map(_.title))
      }

    povs.map { pov =>
      for {
        user1 <- gameUserJson(pov.player)
        user2 <- gameUserJson(pov.opponent)
      } yield Json.obj(
        "id"       -> pov.gameId,
        "fen"      -> (chess.format.Forsyth exportBoard pov.game.board),
        "color"    -> pov.player.color.name,
        "lastMove" -> ~pov.game.lastMoveKeys,
        "user1"    -> user1,
        "user2"    -> user2
      )
    }.sequenceFu map { games =>
      Chart(
        question = JsonQuestion fromQuestion question,
        xAxis = xAxis,
        valueYaxis = Yaxis(metric.name, metric.dataType.name),
        sizeYaxis = Yaxis(metric.per.tellNumber, InsightMetric.DataType.Count.name),
        series = sortedSeries,
        sizeSerie = sizeSerie,
        games = games
      )
    }
  }
}
