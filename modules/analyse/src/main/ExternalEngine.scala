package lila.analyse

import AnalyseBsonHandlers.externalEngineHandler
import chess.variant.Variant
import play.api.data._
import play.api.data.Forms._
import play.api.libs.json.{ Json, OWrites }
import scala.concurrent.ExecutionContext

import lila.common.Form._
import lila.common.{ SecureRandom, ThreadLocalRandom }
import lila.db.dsl._
import lila.user.User

case class ExternalEngine(
    _id: String, // random
    name: String,
    maxThreads: Int,
    maxHash: Int,
    variants: List[String],
    officialStockfish: Boolean,   // Admissible for cloud evals
    providerSecret: String,       // Chosen at random by the provider, possibly shared between registrations
    providerData: Option[String], // Arbitrary string the provider can use to store associated data
    userId: User.ID,              // The user it has been registered for
    clientSecret: String          // Secret unique id of the registration
) {}

object ExternalEngine {

  case class FormData(
      name: String,
      maxThreads: Int,
      maxHash: Int,
      variants: Option[List[String]],
      officialStockfish: Option[Boolean],
      providerSecret: String,
      providerData: Option[String]
  ) {
    def make(userId: User.ID) = ExternalEngine(
      _id = s"eei_${ThreadLocalRandom.nextString(12)}",
      name = name,
      maxThreads = maxThreads,
      maxHash = maxHash,
      variants = variants.filter(_.nonEmpty) | List(chess.variant.Standard.key),
      officialStockfish = ~officialStockfish,
      providerSecret = providerSecret,
      providerData = providerData,
      userId = userId,
      clientSecret = s"ees_${SecureRandom.nextString(16)}"
    )
    def update(engine: ExternalEngine) = make(engine.userId).copy(
      _id = engine._id,
      clientSecret = engine.clientSecret
    )
  }

  val form = Form(
    mapping(
      "name"       -> cleanNonEmptyText(3, 200),
      "maxThreads" -> number(1, 65_536),
      "maxHash"    -> number(1, 1_048_576),
      "variants" -> optional(list {
        stringIn(chess.variant.Variant.all.filterNot(chess.variant.FromPosition ==).map(_.key).toSet)
      }),
      "officialStockfish" -> optional(boolean),
      "providerSecret"    -> nonEmptyText(16, 1024),
      "providerData"      -> optional(text(maxLength = 8192))
    )(FormData.apply)(FormData.unapply)
  )

  implicit val jsonWrites: OWrites[ExternalEngine] = OWrites { e =>
    Json
      .obj(
        "id"           -> e._id,
        "name"         -> e.name,
        "userId"       -> e.userId,
        "maxThreads"   -> e.maxThreads,
        "maxHash"      -> e.maxHash,
        "variants"     -> e.variants,
        "providerData" -> e.providerData,
        "clientSecret" -> e.clientSecret
      )
      .add("officialStockfish" -> e.officialStockfish)
  }
}

final class ExternalEngineApi(coll: Coll)(implicit ec: ExecutionContext) {

  def create(by: User, data: ExternalEngine.FormData): Fu[ExternalEngine] = {
    val engine = data make by.id
    coll.insert.one(engine) inject engine
  }

  def list(by: User): Fu[List[ExternalEngine]] = coll.list[ExternalEngine]($doc("userId" -> by.id), 100)

  def find(by: User, id: String): Fu[Option[ExternalEngine]] =
    coll.one($doc("userId" -> by.id) ++ $id(id))

  def update(prev: ExternalEngine, data: ExternalEngine.FormData): Fu[ExternalEngine] = {
    val engine = data update prev
    coll.update.one($id(engine._id), engine) inject engine
  }

  def delete(by: User, id: String): Fu[Boolean] =
    coll.delete.one($doc("userId" -> by.id) ++ $id(id)).map(_.n > 0)
}
