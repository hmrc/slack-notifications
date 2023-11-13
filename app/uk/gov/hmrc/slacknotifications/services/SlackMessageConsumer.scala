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

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ThrottleMode
import akka.stream.scaladsl.{Sink, Source}
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.slacknotifications.connectors.RateLimitExceededException
import uk.gov.hmrc.slacknotifications.persistence.SlackMessageQueueRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlackMessageConsumer @Inject()(
  slackMessageQueue  : SlackMessageQueueRepository,
  notificationService: NotificationService
)(implicit
  ec         : ExecutionContext,
  actorSystem: ActorSystem
) extends Logging {

  def runQueue()(implicit hc: HeaderCarrier): Future[Done] =
    slackMessageQueue.pullAllOutstanding.flatMap { workItems =>
      val groupedByChannel = workItems.groupBy(_.item.slackMessage.channel)

      Future.sequence(
        groupedByChannel.map { case (channel, messages) =>
          Source(messages)
            .throttle(elements = 1, per = 1.second, maximumBurst = 1, mode = ThrottleMode.Shaping)
            .mapAsync(parallelism = 1)(notificationService.processMessageFromQueue)
            .runWith(Sink.ignore)
            .recover {
              case _: RateLimitExceededException =>
                logger.warn(s"Slack Rate Limit Exceeded - marking all in-progress work items back to to-do and ending current run.")
                slackMessageQueue.resetInProgress()
                Done
              case ex =>
                logger.error(s"Failed to send message to channel: $channel due to ${ex.getMessage}", ex)
                Done
            }
        }
      ).map(_ => Done)
    }
}
