import play.core.PlayVersion
import sbt._

object AppDependencies {

  val bootstrapPlayVersion = "9.8.0"
  val hmrcMongoVersion     = "2.5.0"

  val compile = Seq(
    "uk.gov.hmrc"           %% "bootstrap-backend-play-30"         % bootstrapPlayVersion,
    "uk.gov.hmrc"           %% "internal-auth-client-play-30"      % "3.0.0",
    "uk.gov.hmrc.mongo"     %% "hmrc-mongo-work-item-repo-play-30" % hmrcMongoVersion,
    "org.typelevel"         %% "cats-core"                         % "2.13.0"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"  % bootstrapPlayVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30" % hmrcMongoVersion     % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"         % "3.2.18.0"           % Test
  )
}
