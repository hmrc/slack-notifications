package uk.gov.hmrc.slacknotifications.connectors

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.hooks.HttpHook
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.http.ws.WSHttp
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector._

class UserManagementConnectorSpec
    extends WordSpec
    with Matchers
    with MockitoSugar
    with ScalaFutures
    with BeforeAndAfterEach
    with GuiceOneAppPerSuite {

  val Port           = 8080
  val Host           = "localhost"
  val wireMockServer = new WireMockServer(wireMockConfig().port(Port))

  override def beforeEach {
    wireMockServer.start()
    WireMock.configureFor(Host, Port)
  }

  override def afterEach {
    wireMockServer.stop()
  }

  val httpClient = new HttpClient with WSHttp {
    override val hooks: Seq[HttpHook] = Seq.empty
  }

  "The connector" should {

    implicit val hc = HeaderCarrier()
    val ump = new UserManagementConnector(
      httpClient,
      Configuration("microservice.services.user-management.url" -> s"http://$Host:$Port"),
      Environment.simple())

    "getAllUsers of the organisation" in {

      stubFor(
        get(urlEqualTo("/v2/organisations/users"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""{ "users": [{ "github": "https://github.com/abc", "username": "abc" } ]}""")))

      ump.getAllUsers.futureValue shouldBe List(UmpUser(Some("https://github.com/abc"), Some("abc")))
    }

    "get all the teams for a specified user" in {

      stubFor(
        get(urlEqualTo("/v2/organisations/users/ldapUsername/teams"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |{
                          |  "teams": [
                          |    {
                          |      "slack": "foo/team-A",
                          |      "slackNotification": "foo/team-A-notifications",
                          |      "team": "team-A"
                          |    }
                          |  ]
                          |}""".stripMargin)))

      ump.getTeamsForUser("ldapUsername").futureValue shouldBe List(
        TeamDetails(Some("foo/team-A"), Some("foo/team-A-notifications"), "team-A"))
    }

    "get team details" in {

      stubFor(
        get(urlEqualTo("/v2/organisations/teams/team-A"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |{
                          |  "slack": "foo/team-A",
                          |  "slackNotification": "foo/team-A-notifications",
                          |  "team": "team-A"
                          |}
                          |""".stripMargin)))

      ump.getTeamDetails("team-A").futureValue shouldBe
        Some(TeamDetails(Some("foo/team-A"), Some("foo/team-A-notifications"), "team-A"))
    }

  }

}
