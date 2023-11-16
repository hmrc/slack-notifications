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

package uk.gov.hmrc.slacknotifications.scheduler

import akka.actor.ActorSystem
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.slacknotifications.persistence.MongoLockRepository
import uk.gov.hmrc.slacknotifications.services.SlackMessageConsumer
import uk.gov.hmrc.slacknotifications.utils.{SchedulerConfig, SchedulerUtils}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

@Singleton
class SlackMessageScheduler @Inject()(
  configuration       : Configuration,
  mongoLockRepository : MongoLockRepository,
  slackMessageConsumer: SlackMessageConsumer,
  timestampSupport    : TimestampSupport
)(implicit
  ec                  : ExecutionContext,
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle
) extends SchedulerUtils {

  private val schedulerConfig = {
    val enabledKey = "slackMessageScheduler.enabled"
    SchedulerConfig(
      enabledKey   = enabledKey,
      enabled      = configuration.get[Boolean](enabledKey),
      interval     = configuration.get[FiniteDuration]("slackMessageScheduler.interval"),
      initialDelay = configuration.get[FiniteDuration]("slackMessageScheduler.initialDelay")
    )
  }

  private val lock =
    ScheduledLockService(mongoLockRepository, "slack-message-scheduler", timestampSupport, schedulerConfig.interval)

  implicit val hc: HeaderCarrier = HeaderCarrier()

  scheduleWithTimePeriodLock(
    "Slack Message Scheduler",
    schedulerConfig,
    lock
  ) {
    logger.info("Processing queued Slack messages...")

    for {
      _ <- slackMessageConsumer.runQueue()
      _ =  logger.info("Finished processing queued Slack messages")
    } yield ()
  }

}
