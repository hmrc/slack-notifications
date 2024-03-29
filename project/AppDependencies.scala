import play.core.PlayVersion
import play.sbt.PlayImport._
import sbt._

object AppDependencies {

  val bootstrapPlayVersion = "8.1.0"
  val hmrcMongoVersion = "1.6.0"

  val compile = Seq(
    "uk.gov.hmrc"           %% "bootstrap-backend-play-30"         % bootstrapPlayVersion,
    "uk.gov.hmrc"           %% "internal-auth-client-play-30"      % "1.8.0",
    "uk.gov.hmrc.mongo"     %% "hmrc-mongo-work-item-repo-play-30" % hmrcMongoVersion,
    "org.typelevel"         %% "cats-core"                         % "2.10.0",
    ws,
    ehcache
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"  % bootstrapPlayVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30" % hmrcMongoVersion     % Test,
    "org.scalatestplus"      %% "scalacheck-1-14"         % "3.2.2.0"            % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.17.29"            % Test
  )
}
