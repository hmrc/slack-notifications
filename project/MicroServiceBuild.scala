import play.core.PlayVersion
import play.sbt.PlayImport._
import sbt._

object MicroServiceBuild extends Build with MicroService {

  val appName = "slack-notifications"

  override lazy val appDependencies: Seq[ModuleID] = compile ++ test

  val compile = Seq(
    "uk.gov.hmrc" %% "play-reactivemongo" % "6.2.0",
    ws,
    "uk.gov.hmrc" %% "bootstrap-play-25" % "1.3.0"
  )

  val test = Seq(
    "org.scalatest"     %% "scalatest" % "3.0.4",
    "org.pegdown"       % "pegdown"    % "1.6.0",
    "com.typesafe.play" %% "play-test" % PlayVersion.current
  ).map(_ % "test")

}
