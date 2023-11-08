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

package uk.gov.hmrc.slacknotifications.persistence

import org.bson.types.ObjectId
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
import play.api.Configuration
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.play.json.formats.MongoUuidFormats
import uk.gov.hmrc.mongo.workitem.{WorkItem, WorkItemFields, WorkItemRepository}
import uk.gov.hmrc.slacknotifications.model.QueuedSlackMessage

import java.time.{Duration, Instant}
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlackMessageQueueRepository @Inject()(
  configuration: Configuration,
  mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends WorkItemRepository[QueuedSlackMessage](
  collectionName = "slackMessageQueue",
  mongoComponent = mongoComponent,
  itemFormat     = QueuedSlackMessage.format,
  workItemFields = WorkItemFields.default,
  extraIndexes = Seq(
    IndexModel(Indexes.hashed("item.msgId"), IndexOptions().name("msgId-idx").background(true)),
    IndexModel(Indexes.descending("updatedAt"), IndexOptions().name("ttl-idx").expireAfter(30, TimeUnit.DAYS))
  ),
  extraCodecs = Seq(Codecs.playFormatCodec(MongoUuidFormats.uuidFormat))
){
  override def now(): Instant =
    Instant.now()

  override def inProgressRetryAfter: Duration =
    Duration.ofMillis(configuration.getMillis("queue.retryAfter"))

  def add(item: QueuedSlackMessage): Future[ObjectId] = {
    pushNew(item)
      .map(_.id)
  }

  def getByMsgId(msgId: UUID): Future[Seq[WorkItem[QueuedSlackMessage]]] =
    collection
      .find(Filters.equal("item.msgId", msgId.toString)) // it's stored as a String in Mongo to make querying using mongo shell easier
      .toFuture()
}
