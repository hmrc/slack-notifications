/*
 * Copyright 2023 HM Revenue & Customs
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
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Configuration
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, _}
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig
import uk.gov.hmrc.slacknotifications.config.SlackConfig
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.{TeamDetails, TeamName}
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, SlackConnector, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.model.ChannelLookup.{GithubRepository, GithubTeam, SlackChannel, TeamsOfGithubUser, TeamsOfLdapUser}
import uk.gov.hmrc.slacknotifications.model._
import uk.gov.hmrc.slacknotifications.services.AuthService.Service
import uk.gov.hmrc.slacknotifications.services.NotificationService._
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class NotificationServiceSpec
  extends UnitSpec
     with ScalaFutures
     with IntegrationPatience
     with ScalaCheckPropertyChecks {

  implicit val hc: HeaderCarrier = HeaderCarrier()

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
            attachments = Nil,
            showAttachmentAuthor = true
          ),
          Service("", Password(""))
        )
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
              attachments = Nil,
              showAttachmentAuthor = true
            ),
            Service("", Password(""))
          )
          .futureValue

        result shouldBe NotificationResult().addError(SlackError(statusCode, errorMsg, "nonexistentChannel", None))
      }
    }

    "return an error if an exception was thrown by the Slack connector" in new Fixtures {
      val errorMsg = "some exception message"

      val exceptionsAndErrors =
        Table(
          ("exception", "expected error"),
          (UpstreamErrorResponse(errorMsg, 400, 400), SlackError(400, errorMsg, "name-of-a-channel", None)),
          (UpstreamErrorResponse(errorMsg, 403, 403), SlackError(403, errorMsg, "name-of-a-channel", None)),
          (UpstreamErrorResponse(errorMsg, 500, 500), SlackError(500, errorMsg, "name-of-a-channel", None)),
          (UpstreamErrorResponse(errorMsg, 404, 404), SlackError(404, errorMsg, "name-of-a-channel", None))
        )

      forAll(exceptionsAndErrors) { (exception, expectedError) =>
        when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
          .thenReturn(Future.failed(exception))

        val result = service
          .sendSlackMessage(
            SlackMessage(
              channel     = "name-of-a-channel",
              text        = "text",
              username    = "someUser",
              icon_emoji  = Some(":snowman:"),
              attachments = Nil,
              showAttachmentAuthor = true
            ),
            Service("", Password(""))
          )
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
                attachments = Nil,
                showAttachmentAuthor = true
              ),
              Service("", Password("")))
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
      val usersTeams = List(TeamName("team-one"))
      val teamDetails = TeamDetails(slack = Some(s"https://foo.slack.com/$teamChannel"), teamName = "team-one", slackNotification = None)

      val channelLookups = List(
        GithubRepository("repo"),
        SlackChannel(NonEmptyList.of(teamChannel)),
        TeamsOfGithubUser("a-github-handle"),
        TeamsOfLdapUser("a-ldap-user"),
        GithubTeam("a-github-team")
      )

      when(userManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementService.getTeamsForLdapUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementConnector.getTeamSlackDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(teamDetails)))
      when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      channelLookups.foreach { channelLookup =>
        val notificationRequest =
          NotificationRequest(
            channelLookup = channelLookup,
            messageDetails = MessageDetails(
              text        = "some-text-to-post-to-slack",
              attachments = Nil
            )
          )

        val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List(teamChannel),
          errors             = Nil,
          exclusions         = Nil
        )

        channelLookup match {
          case req: TeamsOfGithubUser => verify(userManagementService,   times(1)).getTeamsForGithubUser(eqTo(req.githubUsername))(any)
          case req: TeamsOfLdapUser   => verify(userManagementService,   times(1)).getTeamsForLdapUser(eqTo(req.ldapUsername))(any)
          case req: GithubTeam        => verify(userManagementConnector, times(1)).getTeamSlackDetails(eqTo(req.teamName))(any)
          case _                      =>
        }
      }
    }

    "work for all channel lookup types (happy path scenarios)with trailing / at the end" in new Fixtures {
      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(RepositoryDetails(teamNames = List(teamName), owningTeams = Nil))))

      val teamChannel = "team-channel"
      val usersTeams = List(TeamName("team-one"))
      val teamDetails = TeamDetails(teamName = "team-one", slack = Some(s"https://foo.slack.com/$teamChannel/"), slackNotification = None)

      val channelLookups = List(
        GithubRepository("repo"),
        SlackChannel(NonEmptyList.of(teamChannel)),
        TeamsOfGithubUser("a-github-handle"),
        TeamsOfLdapUser("a-ldap-user"),
        GithubTeam("a-github-team")
      )

      when(userManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementService.getTeamsForLdapUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementConnector.getTeamSlackDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(teamDetails)))
      when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      channelLookups.foreach { channelLookup =>
        val notificationRequest =
          NotificationRequest(
            channelLookup = channelLookup,
            messageDetails = MessageDetails(
              text        = "some-text-to-post-to-slack",
              attachments = Nil
            )
          )

        val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List(teamChannel),
          errors             = Nil,
          exclusions         = Nil
        )
      }
    }

    "Send the message to an admin channel if no slack channel is configured in UMP" in new Fixtures {
      override val configuration = Configuration(
        "slack.notification.enabled"         -> true,
        "alerts.slack.noTeamFound.channel"   -> "slack-channel-missing",
        "alerts.slack.noTeamFound.username"  -> "slack-notifications",
        "alerts.slack.noTeamFound.iconEmoji" -> "",
        "alerts.slack.noTeamFound.text"      -> "test {user}"
      )

      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(RepositoryDetails(teamNames = List(teamName), owningTeams = Nil))))

      val usersTeams = List(TeamName("team-name"))

      val channelLookups = List(
        GithubRepository("repo"),
        TeamsOfGithubUser("a-github-handle"),
        TeamsOfLdapUser("a-ldap-user"),
        GithubTeam(teamName)
      )

      when(userManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementService.getTeamsForLdapUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementConnector.getTeamSlackDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(TeamDetails(slack = None, slackNotification = None, teamName = teamName))))
      when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      channelLookups.foreach { channelLookup =>
        val notificationRequest =
          NotificationRequest(
            channelLookup = channelLookup,
            messageDetails = MessageDetails(
              text        = "some-text-to-post-to-slack",
              attachments = Nil
            )
          )

        val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List("slack-channel-missing"),
          errors             = Seq(UnableToFindTeamSlackChannelInUMP(teamName)),
          exclusions         = Nil
        )
      }
    }

    "Send the message to an admin channel if the team does not exist in UMP" in new Fixtures {
      override val configuration = Configuration(
        "slack.notification.enabled"         -> true,
        "alerts.slack.noTeamFound.channel"   -> "slack-channel-missing",
        "alerts.slack.noTeamFound.username"  -> "slack-notifications",
        "alerts.slack.noTeamFound.iconEmoji" -> "",
        "alerts.slack.noTeamFound.text"      -> "test {user}"
      )

      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(RepositoryDetails(teamNames = List(teamName), owningTeams = Nil))))

      val usersTeams = List(TeamName("team-name"))

      val channelLookups = List(
        GithubRepository("repo"),
        TeamsOfGithubUser("a-github-handle"),
        TeamsOfLdapUser("a-ldap-user"),
        GithubTeam(teamName)
      )

      when(userManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementService.getTeamsForLdapUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementConnector.getTeamSlackDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(None))
      when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      channelLookups.foreach { channelLookup =>
        val notificationRequest =
          NotificationRequest(
            channelLookup = channelLookup,
            messageDetails = MessageDetails(
              text        = "some-text-to-post-to-slack",
              attachments = Nil
            )
          )

        val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List("slack-channel-missing"),
          errors             = Seq(UnableToFindTeamSlackChannelInUMP(teamName)),
          exclusions         = Nil
        )
      }
    }

    "report if no teams are found for a github user" in new Fixtures {
      val githubUsername = "a-github-username"
      val notificationRequest =
        NotificationRequest(
          channelLookup  = TeamsOfGithubUser(githubUsername),
          messageDetails = exampleMessageDetails
        )

      when(userManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(List.empty))

      when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(TeamsNotFoundForUsername("github", githubUsername)),
        exclusions         = Nil
      )
    }

    "report if no teams are found for a ldap user" in new Fixtures {
      val ldapUsername = "a-ldap-username"
      val notificationRequest =
        NotificationRequest(
          channelLookup  = TeamsOfLdapUser(ldapUsername),
          messageDetails = exampleMessageDetails
        )

      when(userManagementService.getTeamsForLdapUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(List.empty))

      when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(TeamsNotFoundForUsername("ldap", ldapUsername)),
        exclusions         = Nil
      )
    }
  }

  "Disabled notifications" should {
    "not send alerts for all channel lookup types" in new Fixtures {
      private val teamName = "team-name"
      override val configuration = Configuration("slack.notification.enabled" -> false)
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(RepositoryDetails(teamNames = List(teamName), owningTeams = Nil))))

      val teamChannel = "team-channel"
      val usersTeams = List(TeamName("team-name"))
      val teamDetails = TeamDetails(slack = Some(s"https://foo.slack.com/$teamChannel"), slackNotification = None, teamName = "n/a")

      val channelLookups = List(
        GithubRepository("repo"),
        SlackChannel(NonEmptyList.of(teamChannel)),
        TeamsOfGithubUser("a-github-handle"),
        TeamsOfLdapUser("a-ldap-user"),
        GithubTeam("a-github-team")
      )

      when(userManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementService.getTeamsForLdapUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementConnector.getTeamSlackDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(teamDetails)))

      channelLookups.foreach { channelLookup =>
        val notificationRequest =
          NotificationRequest(
            channelLookup = channelLookup,
            messageDetails = MessageDetails(
              text = "some-text-to-post-to-slack",
              attachments = Nil
            )
          )

        val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue
        val space = " " //to stop ide from removing end spaces

        val slackMessageStr = s"""
                               |   Channel: team-channel
                               |   Message: some-text-to-post-to-slack
                               |   Username:$space
                               |   Emoji:$space
                               |""".stripMargin

        result shouldBe NotificationResult(
          successfullySentTo = List(),
          errors = List(),
          exclusions = List(NotificationDisabled(slackMessageStr)))
      }
    }
  }

  "Sending a request for teams of a github user" should {
    "not include excluded teams based on configuration" in new Fixtures {
      val teamName1              = "team-to-be-excluded-1"
      val teamName2              = "team-to-be-excluded-2"
      override val configuration = Configuration(
        "exclusions.notRealTeams"            -> s"$teamName1, $teamName2",
        "slack.notification.enabled"         -> true,
        "alerts.slack.noTeamFound.channel"   -> "test-channel",
        "alerts.slack.noTeamFound.username"  -> "slack-notifications",
        "alerts.slack.noTeamFound.iconEmoji" -> "",
        "alerts.slack.noTeamFound.text"      -> "test {user}"

      )
      val githubUsername = "a-github-username"
      val usersTeams     = List(TeamName(teamName1), TeamName(teamName2))

      val notificationRequest =
        NotificationRequest(
          channelLookup  = TeamsOfGithubUser(githubUsername),
          messageDetails = exampleMessageDetails
        )

      when(userManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))

      when(slackConnector.sendMessage(any[SlackMessage])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(TeamsNotFoundForUsername("github", githubUsername)),
        exclusions         = List(NotARealTeam(teamName1), NotARealTeam(teamName2))
      )
    }
    "not include ignored github user names, e.g. LDS dummy commiter for admin endpoints" in new Fixtures {
      val ignored1               = "n/1"
      val ignored2               = "ignored2"
      override val configuration = Configuration(
        "exclusions.notRealGithubUsers"      -> s"$ignored1, $ignored2",
        "slack.notification.enabled"         -> true
      )

      val notificationRequest =
        NotificationRequest(
          channelLookup  = TeamsOfGithubUser(ignored1),
          messageDetails = exampleMessageDetails
        )

      val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue

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

      override val configuration = Configuration(
        "exclusions.notRealTeams"            -> s"$teamName1, $teamName2",
        "slack.notification.enabled"         -> true
      )

      val notificationRequest =
        NotificationRequest(
          channelLookup  = GithubRepository("repo"),
          messageDetails = exampleMessageDetails
        )

      val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue

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
          channelLookup  = GithubRepository(nonexistentRepoName),
          messageDetails = exampleMessageDetails
        )

      val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(RepositoryNotFound(nonexistentRepoName)),
        exclusions         = Nil
      )
    }

    "report if no team is assigned to a repository" in new Fixtures {
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(RepositoryDetails(teamNames = List(), owningTeams = Nil))))

      val repoName = "repo-name"
      private val notificationRequest =
        NotificationRequest(
          channelLookup  = GithubRepository(repoName),
          messageDetails = exampleMessageDetails
        )

      val result = service.sendNotification(notificationRequest, Service("", Password(""))).futureValue

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
      val teamDetails     = TeamDetails(slack = Some(slackLink), slackNotification = None, teamName = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe Some(teamChannelName)
    }

    "return the slackNotification channel when present" in new Fixtures {
      val teamChannelName = "teamChannel"
      val slackLink       = "foo/" + teamChannelName
      val teamDetails = TeamDetails(
        slack             = Some(slackLink),
        slackNotification = Some(s"foo/$teamChannelName-notification"),
        teamName              = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe Some(s"$teamChannelName-notification")
    }

    "return None if slack field exists but there is no slack channel in it" in new Fixtures {
      val slackLink   = "link-without-team/"
      val teamDetails = TeamDetails(slack = Some(slackLink), slackNotification = None, teamName = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe None
    }

    "return None if slack field doesn't exist" in new Fixtures {
      val teamDetails = TeamDetails(slack = None, slackNotification = None, teamName = "n/a")
      service.extractSlackChannel(teamDetails) shouldBe None
    }

    "return None if slack field does not contain a forward slash" in new Fixtures {
      val teamDetails = TeamDetails(slack = Some("not a url"), slackNotification = None, teamName = "n/a")
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
              None)),
          showAttachmentAuthor = true
        ),
        Service("leak-detection", Password(""))
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
              None)),
          showAttachmentAuthor = true
        ),
        Service("another-service", Password(""))
      )

      result.username                     should be("another-service")
      result.icon_emoji                   should be(None)
      result.attachments.head.author_name should be(Some("another-service"))
      result.attachments.head.author_icon should be(None)
    }

    "Don't set author name when showAttachmentAuthor=false" in new Fixtures {
      val result = service.populateNameAndIconInMessage(
        SlackMessage(
          channel = "",
          text = "",
          username = "",
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
              None)),
          showAttachmentAuthor = false
        ),
        Service("another-service", Password(""))
      )
      result.attachments.head.author_name should be(None)
    }
  }

  trait Fixtures {
    val slackConnector                = mock[SlackConnector]//(withSettings.lenient)
    val teamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]//(withSettings.lenient)
    val userManagementConnector       = mock[UserManagementConnector]//(withSettings.lenient)
    val userManagementService         = mock[UserManagementService]

    val configuration =
      Configuration(
        "auth.authorizedServices.0.name"        -> "leak-detection",
        "auth.authorizedServices.0.password"    -> "foo",
        "auth.authorizedServices.0.displayName" -> "leak-detector",
        "auth.authorizedServices.1.name"        -> "another-service",
        "auth.authorizedServices.1.password"    -> "foo",
        "slack.notification.enabled"            -> true,
        "alerts.slack.noTeamFound.channel"      -> "test-channel",
        "alerts.slack.noTeamFound.username"     -> "slack-notifications",
        "alerts.slack.noTeamFound.iconEmoji"    -> "",
        "alerts.slack.noTeamFound.text"         -> "test {user}"

      )

    val exampleMessageDetails =
      MessageDetails(
        text        = "some-text-to-post-to-slack",
        attachments = Nil
      )

    lazy val service =
      new NotificationService(
        new SlackNotificationConfig(configuration),
        slackConnector,
        new SlackConfig(configuration),
        teamsAndRepositoriesConnector,
        userManagementConnector,
        userManagementService
      )
  }
}
