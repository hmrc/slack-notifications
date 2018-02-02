/*
 * Copyright 2018 HM Revenue & Customs
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

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import cats.implicits._
import javax.inject.Inject
import play.api.Logger
import play.api.libs.json.{Format, JsValue, Json}
import scala.concurrent.Future
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import uk.gov.hmrc.slacknotifications.connectors.{SlackConnector, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.model.ChannelLookup.GithubRepository
import uk.gov.hmrc.slacknotifications.model.{NotificationRequest, SlackMessage}

class NotificationService @Inject()(
  slackConnector: SlackConnector,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  userManagementConnector: UserManagementConnector) {
  import NotificationService._

  def sendNotification(notificationRequest: NotificationRequest)(
    implicit hc: HeaderCarrier): Future[ValidatedNel[Error, Unit]] =
    notificationRequest.channelLookup match {
      case GithubRepository(_, name) =>
        teamsAndRepositoriesConnector.getRepositoryDetails(name).flatMap {
          case Some(repositoryDetails) =>
            repositoryDetails.teamNames.toNel match {
              case None => Future.successful(TeamsNotFoundForRepository(name).invalidNel)
              case Some(teamNames) =>
                val foo = teamNames map { t =>
                  val slackChannelF = {
                    val x = userManagementConnector.getTeamSlackChannel(t)
                    x.map { r =>
                      val y = extractSlackChannel(r.json)
                      y
                    }
                  }

                  slackChannelF.flatMap {
                    case Some(slackChannel) => sendSlackMessage(SlackMessage(slackChannel)).map(_.toValidatedNel)
                    case None               => Future.successful(SlackChannelNotFound(t).invalidNel)
                  }
                }

                val bar = foo.sequence[Future, ValidatedNel[Error, Unit]]

                val baz = bar.map { x =>
                  x.reduceLeft { (acc, c) =>
                    c match {
                      case Valid(_) => acc
                      case Invalid(errors) =>
                        acc match {
                          case Valid(_) => c
                          case Invalid(errorsSoFar) =>
                            errorsSoFar.concatNel(errors).invalid
                        }
                    }

                  }
                }
                baz
            }

          case None =>
            Future.successful(RepositoryNotFound(name).invalidNel)
        }
    }

  private[services] def sendSlackMessage(slackMessage: SlackMessage)(
    implicit hc: HeaderCarrier): Future[Either[OtherError, Unit]] =
    slackConnector.sendMessage(slackMessage).map { response =>
      response.status match {
        case 200 => Right(())
        case _ =>
          Logger.warn(s"Slack API returned error, status=${response.status} and body='${response.body}'")
          Left(OtherError(response.status, response.body))
      }
    }

  private[services] def extractSlackChannel(json: JsValue): Option[String] =
    for {
      js           <- Option(json)
      teamDetails  <- js.asOpt[TeamDetails]
      slackChannel <- teamDetails.slackChannel
    } yield slackChannel
}

object NotificationService {
  sealed trait Error extends Product with Serializable
  final case class OtherError(statusCode: Int, message: String) extends Error
  final case class RepositoryNotFound(repoName: String) extends Error
  final case class TeamsNotFoundForRepository(repoName: String) extends Error
  final case class SlackChannelNotFound(teamName: String) extends Error

  case class TeamDetails(slack: String) {
    def slackChannel: Option[String] = {
      val s = slack.substring(slack.lastIndexOf("/") + 1)
      if (s.nonEmpty) Some(s) else None
    }
  }

  object TeamDetails {
    implicit val format: Format[TeamDetails] = Json.format[TeamDetails]
  }
}
