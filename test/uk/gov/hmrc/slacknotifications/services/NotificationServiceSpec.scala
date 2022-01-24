/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.slacknotifications.services

import cats.data.NonEmptyList
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Configuration
import uk.gov.hmrc.http.{BadRequestException, HeaderCarrier, HttpResponse, NotFoundException, _}
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamDetails
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, SlackConnector, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.model.ChannelLookup.{GithubRepository, SlackChannel, TeamsOfGithubUser}
import uk.gov.hmrc.slacknotifications.model.{Attachment, MessageDetails, NotificationRequest, SlackMessage}
import uk.gov.hmrc.slacknotifications.services.AuthService.Service
import uk.gov.hmrc.slacknotifications.services.NotificationService._
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class NotificationServiceSpec
    extends UnitSpec
    with ScalaFutures
    with ScalaCheckPropertyChecks {

  implicit val hc: HeaderCarrier                       = HeaderCarrier()
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.second, 15.millis)

  "Sending a Slack message" should {
    "succeed if slack accepted the notification" in new Fixtures {
      when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      private val existingChannel = "existingChannel"
      val result = service
        .sendSlackMessage(
          SlackMessage(
            channel     = existingChannel,
            text        = "text",
            username    = "someUser",
            icon_emoji  = Some(":snowman:"),
            attachments = Nil),
          Service("", ""))
        .futureValue

      result shouldBe NotificationResult().addSuccessfullySent(existingChannel)
    }

    "return error if response was not 200" in new Fixtures {
      val invalidStatusCodes = Gen.choose(201, 599)
      forAll(invalidStatusCodes) { statusCode =>
        val errorMsg = "invalid_payload"
        when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
          .thenReturn(Future.successful(HttpResponse(statusCode, errorMsg)))

        val result = service
          .sendSlackMessage(
            SlackMessage(
              channel     = "nonexistentChannel",
              text        = "text",
              username    = "someUser",
              icon_emoji  = Some(":snowman:"),
              attachments = Nil),
            Service("", ""))
          .futureValue

        result shouldBe NotificationResult().addError(SlackError(statusCode, errorMsg))
      }
    }

    "return an error if an exception was thrown by the Slack connector" in new Fixtures {
      val errorMsg = "some exception message"

      val exceptionsAndErrors =
        Table(
          ("exception", "expected error"),
          (new BadRequestException(errorMsg), SlackError(400, errorMsg)),
          (UpstreamErrorResponse(errorMsg, 403, 403), SlackError(403, errorMsg)),
          (UpstreamErrorResponse(errorMsg, 500, 500), SlackError(500, errorMsg)),
          (new NotFoundException(errorMsg), SlackError(404, errorMsg))
        )

      forAll(exceptionsAndErrors) { (exception, expectedError) =>
        when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
          .thenReturn(Future.failed(exception))

        val result = service
          .sendSlackMessage(
            SlackMessage(
              channel     = "channel",
              text        = "text",
              username    = "someUser",
              icon_emoji  = Some(":snowman:"),
              attachments = Nil),
            Service("", ""))
          .futureValue

        result.errors.head shouldBe expectedError
      }
    }

    "throw an exception if any other non-fatal exception was thrown" in new Fixtures {
      val errorMsg = "non-fatal exception"

      when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
        .thenReturn(Future.failed(new RuntimeException(errorMsg)))

      val thrown =
        the[RuntimeException] thrownBy {
          service
            .sendSlackMessage(
              SlackMessage(
                channel     = "channel",
                text        = "text",
                username    = "someUser",
                icon_emoji  = Some(":snowman:"),
                attachments = Nil),
              Service("", ""))
            .futureValue
        }

      thrown.getCause.getMessage shouldBe errorMsg
    }
  }

  "Sending a notification" should {
    "work for all channel lookup types (happy path scenarios)" in new Fixtures {
      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(RepositoryDetails(teamNames = List(teamName), owningTeams = Nil))))

      val teamChannel = "team-channel"
      val teamDetails = TeamDetails(slack = Some(s"https://foo.slack.com/$teamChannel"), None, team = "n/a")

      val channelLookups = List(
        GithubRepository("", "repo"),
        SlackChannel("", NonEmptyList.of(teamChannel)),
        TeamsOfGithubUser("", "a-github-handle")
      )

      channelLookups.foreach { channelLookup =>
        val notificationRequest =
          NotificationRequest(
            channelLookup = channelLookup,
            messageDetails = MessageDetails(
              text        = "some-text-to-post-to-slack",
              attachments = Nil
            )
          )

        when(userManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
          .thenReturn(Future.successful(List(teamDetails)))
        when(userManagementConnector.getTeamDetails(any[String])(any[HeaderCarrier]))
          .thenReturn(Future.successful(Some(teamDetails)))
        when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
          .thenReturn(Future.successful(HttpResponse(200, "")))

        val result = service.sendNotification(notificationRequest, Service("", "")).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List(teamChannel),
          errors             = Nil,
          exclusions         = Nil
        )
      }
    }

    "work for all channel lookup types (happy path scenarios)with trailing / at the end" in new Fixtures {
      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(RepositoryDetails(teamNames = List(teamName), owningTeams = Nil))))

      val teamChannel = "team-channel"
      val teamDetails = TeamDetails(slack = Some(s"https://foo.slack.com/$teamChannel/"), None, team = "n/a")

      val channelLookups = List(
        GithubRepository("", "repo"),
        SlackChannel("", NonEmptyList.of(teamChannel)),
        TeamsOfGithubUser("", "a-github-handle")
      )

      channelLookups.foreach { channelLookup =>
        val notificationRequest =
          NotificationRequest(
            channelLookup = channelLookup,
            messageDetails = MessageDetails(
              text        = "some-text-to-post-to-slack",
              attachments = Nil
            )
          )

        when(userManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
          .thenReturn(Future.successful(List(teamDetails)))
        when(userManagementConnector.getTeamDetails(any[String])(any[HeaderCarrier]))
          .thenReturn(Future.successful(Some(teamDetails)))
        when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
          .thenReturn(Future.successful(HttpResponse(200, "")))

        val result = service.sendNotification(notificationRequest, Service("", "")).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List(teamChannel),
          errors             = Nil,
          exclusions         = Nil
        )
      }
    }

    "report if no teams are found for a user" in new Fixtures {
      val githubUsername = "a-github-username"
      val notificationRequest =
        NotificationRequest(
          channelLookup  = TeamsOfGithubUser("", githubUsername),
          messageDetails = exampleMessageDetails
        )

      when(userManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(List.empty))

      val result = service.sendNotification(notificationRequest, Service("", "")).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(TeamsNotFoundForGithubUsername(githubUsername)),
        exclusions         = Nil
      )
    }
  }

  "Sending a request for teams of a github user" should {
    "not include excluded teams based on configuration" in new Fixtures {
      val teamName1              = "team-to-be-excluded-1"
      val teamName2              = "team-to-be-excluded-2"
      override val configuration = Configuration("exclusions.notRealTeams" -> s"$teamName1, $teamName2")
      val githubUsername         = "a-github-username"

      val notificationRequest =
        NotificationRequest(
          channelLookup  = TeamsOfGithubUser("", githubUsername),
          messageDetails = exampleMessageDetails
        )

      when(userManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(List(TeamDetails(None, None, teamName1), TeamDetails(None, None, teamName2))))

      val result = service.sendNotification(notificationRequest, Service("", "")).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(TeamsNotFoundForGithubUsername(githubUsername)),
        exclusions         = List(NotARealTeam(teamName1), NotARealTeam(teamName2))
      )
    }
    "not include ignored github user names, e.g. LDS dummy commiter for admin endpoints" in new Fixtures {
      val ignored1               = "n/1"
      val ignored2               = "ignored2"
      override val configuration = Configuration("exclusions.notRealGithubUsers" -> s"$ignored1, $ignored2")

      val notificationRequest =
        NotificationRequest(
          channelLookup  = TeamsOfGithubUser("", ignored1),
          messageDetails = exampleMessageDetails
        )

      val result = service.sendNotification(notificationRequest, Service("", "")).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = Nil,
        exclusions         = List(NotARealGithubUser(ignored1))
      )
    }
  }

  "Sending a request with github repository lookup" should {
    "don't send notifications for a predefined set of ignored teams" in new Fixtures {
      private val teamName1 = "team-to-be-excluded-1"
      private val teamName2 = "team-to-be-excluded-2"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(RepositoryDetails(teamNames = List(teamName1, teamName2), owningTeams = Nil))))

      override val configuration = Configuration("exclusions.notRealTeams" -> s"$teamName1, $teamName2")

      val notificationRequest =
        NotificationRequest(
          channelLookup  = GithubRepository("", "repo"),
          messageDetails = exampleMessageDetails
        )

      val result = service.sendNotification(notificationRequest, Service("", "")).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = Nil,
        exclusions         = List(NotARealTeam(teamName1), NotARealTeam(teamName2))
      )
    }

    "report if a repository does not exist" in new Fixtures {
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(None))

      private val nonexistentRepoName = "nonexistent-repo"
      private val notificationRequest =
        NotificationRequest(
          channelLookup  = GithubRepository("", nonexistentRepoName),
          messageDetails = exampleMessageDetails
        )

      val result = service.sendNotification(notificationRequest, Service("", "")).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(RepositoryNotFound(nonexistentRepoName)),
        exclusions         = Nil
      )
    }

    "report if the team name is not found by the user management service" in new Fixtures {
      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(RepositoryDetails(teamNames = List(teamName), owningTeams = Nil))))
      when(userManagementConnector.getTeamDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(None))

      private val notificationRequest =
        NotificationRequest(
          channelLookup  = GithubRepository("", ""),
          messageDetails = exampleMessageDetails
        )

      val result = service.sendNotification(notificationRequest, Service("", "")).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(SlackChannelNotFoundForTeamInUMP(teamName)),
        exclusions         = Nil
      )
    }

    "report if no team is assigned to a repository" in new Fixtures {
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(RepositoryDetails(teamNames = List(), owningTeams = Nil))))

      val repoName = "repo-name"
      private val notificationRequest =
        NotificationRequest(
          channelLookup  = GithubRepository("", repoName),
          messageDetails = exampleMessageDetails
        )

      val result = service.sendNotification(notificationRequest, Service("", "")).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(TeamsNotFoundForRepository(repoName)),
        exclusions         = Nil
      )
    }
  }

  "Getting teams responsible for repo" should {
    "prioritize owningTeams" in new Fixtures {
      val repoDetails =
        RepositoryDetails(
          owningTeams = List("team1"),
          teamNames   = List("team1", "team2")
        )

      service.getTeamsResponsibleForRepo(repoDetails) shouldBe List("team1")
    }
    "return contributing teams if no explicit owning teams are specified" in new Fixtures {
      val repoDetails =
        RepositoryDetails(
          owningTeams = Nil,
          teamNames   = List("team1", "team2")
        )

      service.getTeamsResponsibleForRepo(repoDetails) shouldBe List("team1", "team2")
    }
  }

  "Extracting team slack channel" should {
    "work if slack field exists and contains team name at the end" in new Fixtures {
      val teamChannelName = "teamChannel"
      val slackLink       = "foo/" + teamChannelName
      val teamDetails     = TeamDetails(slack = Some(slackLink), slackNotification = None, team = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe Some(teamChannelName)
    }

    "return the slackNotification channel when present" in new Fixtures {
      val teamChannelName = "teamChannel"
      val slackLink       = "foo/" + teamChannelName
      val teamDetails = TeamDetails(
        slack             = Some(slackLink),
        slackNotification = Some(s"foo/$teamChannelName-notification"),
        team              = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe Some(s"$teamChannelName-notification")
    }

    "return None if slack field exists but there is no slack channel in it" in new Fixtures {
      val slackLink   = "link-without-team/"
      val teamDetails = TeamDetails(slack = Some(slackLink), None, team = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe None
    }

    "return None if slack field doesn't exist" in new Fixtures {
      val teamDetails = TeamDetails(slack = None, None, team = "n/a")
      service.extractSlackChannel(teamDetails) shouldBe None
    }

    "return None if slack field does not contain a forward slash" in new Fixtures {
      val teamDetails = TeamDetails(slack = Some("not a url"), None, team = "n/a")
      service.extractSlackChannel(teamDetails) shouldBe None
    }
  }

  "Sanitising a slack message" should {

    "strip out the emoji and use displayName from config to determine author name" in new Fixtures {
      val result = service.populateNameAndIconInMessage(
        SlackMessage(
          channel    = "",
          text       = "",
          username   = "",
          icon_emoji = Some(":eyes:"),
          attachments = Seq(
            Attachment(
              None,
              None,
              None,
              author_name = Some("username"),
              None,
              Some(":monkey:"),
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None))
        ),
        Service("leak-detection", "")
      )

      result.username                     should be("leak-detector")
      result.icon_emoji                   should be(None)
      result.attachments.head.author_name should be(Some("leak-detector"))
      result.attachments.head.author_icon should be(None)
    }

    "strip out the emoji and use name from config to determine author name when no displayName is configured" in new Fixtures {
      val result = service.populateNameAndIconInMessage(
        SlackMessage(
          channel    = "",
          text       = "",
          username   = "",
          icon_emoji = Some(":eyes:"),
          attachments = Seq(
            Attachment(
              None,
              None,
              None,
              author_name = Some("username"),
              None,
              Some(":monkey:"),
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None))
        ),
        Service("another-service", "")
      )

      result.username                     should be("another-service")
      result.icon_emoji                   should be(None)
      result.attachments.head.author_name should be(Some("another-service"))
      result.attachments.head.author_icon should be(None)
    }
  }

  trait Fixtures {
    val slackConnector                = mock[SlackConnector]
    val teamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
    val userManagementConnector       = mock[UserManagementConnector]
    val userManagementService         = mock[UserManagementService]

    val configuration =
      Configuration(
        "auth.authorizedServices.0.name"        -> "leak-detection",
        "auth.authorizedServices.0.password"    -> "foo",
        "auth.authorizedServices.0.displayName" -> "leak-detector",
        "auth.authorizedServices.1.name"        -> "another-service",
        "auth.authorizedServices.1.password"    -> "foo"
      )

    val exampleMessageDetails =
      MessageDetails(
        text        = "some-text-to-post-to-slack",
        attachments = Nil
      )

    lazy val service =
      new NotificationService(
        configuration,
        slackConnector,
        teamsAndRepositoriesConnector,
        userManagementConnector,
        userManagementService
      )
  }
}
