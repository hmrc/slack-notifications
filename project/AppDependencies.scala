import play.core.PlayVersion
import play.sbt.PlayImport._
import sbt._

object AppDependencies {

  val compile = Seq(
    "org.typelevel"         %% "cats-core"         % "1.6.0",
    "uk.gov.hmrc"           %% "bootstrap-play-25" % "4.9.0",
    "com.github.pureconfig" %% "pureconfig"        % "0.9.2",
    ws,
    cache
  )

  val test = Seq(
    "org.scalatest"          %% "scalatest"          % "3.0.7"             % "test",
    "org.scalacheck"         %% "scalacheck"         % "1.13.4"            % "test",
    "org.scalatestplus.play" %% "scalatestplus-play" % "2.0.1"             % "test",
    "org.pegdown"            % "pegdown"             % "1.6.0"             % "test",
    "org.mockito"            % "mockito-all"         % "1.10.19"           % "test",
    "com.github.tomakehurst" % "wiremock"            % "2.22.0"            % "test",
    "com.typesafe.play"      %% "play-test"          % PlayVersion.current % "test"
  )

}
