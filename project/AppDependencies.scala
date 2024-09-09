import play.core.PlayVersion
import play.sbt.PlayImport._
import sbt._

object AppDependencies {

  val bootstrapPlayVersion = "9.4.0"
  val hmrcMongoVersion = "2.2.0"

  val compile = Seq(
    "uk.gov.hmrc"           %% "bootstrap-backend-play-30"         % bootstrapPlayVersion,
    "uk.gov.hmrc"           %% "internal-auth-client-play-30"      % "3.0.0",
    "uk.gov.hmrc.mongo"     %% "hmrc-mongo-work-item-repo-play-30" % hmrcMongoVersion,
    "org.typelevel"         %% "cats-core"                         % "2.12.0",
    ws,
    ehcache
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"  % bootstrapPlayVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30" % hmrcMongoVersion     % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"         % "3.2.18.0"           % Test
  )
}
