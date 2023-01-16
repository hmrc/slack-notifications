import play.core.PlayVersion
import play.sbt.PlayImport._
import sbt._

object AppDependencies {

  val bootstrapPlayVersion = "7.12.0"

  val compile = Seq(
    "uk.gov.hmrc"           %% "bootstrap-backend-play-28" % bootstrapPlayVersion,
    "org.typelevel"         %% "cats-core"                 % "2.1.1",
    ws,
    ehcache
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"  % bootstrapPlayVersion % Test,
    "org.scalatestplus"      %% "scalacheck-1-14"          % "3.1.1.1"           % Test,
    "org.mockito"            %% "mockito-scala-scalatest"  % "1.13.10"           % Test
  )
}
