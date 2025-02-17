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
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import play.api.libs.json.JsObject
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}
import uk.gov.hmrc.slacknotifications.model.{ChannelLookup, NotificationResult, QueuedSlackMessage, SlackMessage}

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

class SlackMessageQueueRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[WorkItem[QueuedSlackMessage]]
     with ScalaFutures:

  val configuration: Configuration =
    Configuration(
      "slackMessageQueue.retryAfterFailed" -> "5.seconds",
      "slackMessageQueue.retryAfterInProgress" -> "10.minutes",
    )

  override protected val repository: SlackMessageQueueRepository =
    SlackMessageQueueRepository(
      configuration,
      mongoComponent
    )

  def message(id: UUID): QueuedSlackMessage =
    QueuedSlackMessage(
      msgId = id,
      callbackChannel = Some("callback-channel"),
      channelLookup = Some(ChannelLookup.SlackChannel(cats.data.NonEmptyList.one("a-channel"))),
      slackMessage = SlackMessage(
        channel = "a-channel",
        text = "some text",
        blocks = Seq.empty[JsObject],
        attachments = Seq.empty[JsObject],
        username = "test-user",
        emoji = ":robot_face:"
      ),
      result = NotificationResult()
    )

  "add" should:
    "push a new message onto the queue" in:
      val msg: QueuedSlackMessage = message(UUID.randomUUID())

      val workItemId = repository.add(msg).futureValue

      val workItem = repository.findById(workItemId).futureValue.get

      workItem.item shouldBe msg
      workItem.status shouldBe ProcessingStatus.ToDo

  "pullAllOutstanding" should:
    "return all work items that are ready to be processed" in:
      repository.add(message(UUID.randomUUID())).futureValue
      repository.add(message(UUID.randomUUID())).futureValue
      repository.add(message(UUID.randomUUID())).futureValue

      repository.pullAllOutstanding().futureValue.length shouldBe 3

  "getByMsgId" should:
    "return all work items relating to the same msgId" in:
      val id = UUID.randomUUID()

      val workItemIds: Seq[ObjectId] =
        Seq(
          repository.add(message(id)).futureValue,
          repository.add(message(id)).futureValue,
          repository.add(message(id)).futureValue,
        )

      // add some unrelated ones with different ids to ensure the find is working as expected
      repository.add(message(UUID.randomUUID())).futureValue
      repository.add(message(UUID.randomUUID())).futureValue

      val workItems = repository.getByMsgId(id).futureValue

      workItems.length should be(3)
      workItems.map(_.id) should contain theSameElementsAs workItemIds
