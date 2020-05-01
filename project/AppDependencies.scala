import play.core.PlayVersion
import play.sbt.PlayImport._
import sbt._

object AppDependencies {

  val compile = Seq(
    "uk.gov.hmrc"           %% "bootstrap-backend-play-27" % "2.6.0",
    "org.typelevel"         %% "cats-core"                 % "2.1.1",
    "com.github.pureconfig" %% "pureconfig"                % "0.12.3",
    ws,
    ehcache
  )

  val test = Seq(
    "org.scalatest"          %% "scalatest"          % "3.0.8"             % Test,
    "org.scalatestplus.play" %% "scalatestplus-play" % "4.0.3"             % Test,
    "org.scalacheck"         %% "scalacheck"         % "1.14.3"            % Test,
    "org.pegdown"             % "pegdown"            % "1.6.0"             % Test,
    "org.mockito"             % "mockito-core"       % "3.2.4"             % Test,
    "com.github.tomakehurst"  % "wiremock-jre8"      % "2.26.3"            % Test,
    "com.typesafe.play"      %% "play-test"          % PlayVersion.current % Test
  )

}
