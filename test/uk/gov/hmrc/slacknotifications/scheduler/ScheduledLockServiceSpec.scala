package uk.gov.hmrc.slacknotifications.scheduler

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.CurrentTimestampSupport
import uk.gov.hmrc.mongo.lock.Lock
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.slacknotifications.persistence.MongoLockRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class ScheduledLockServiceSpec
  extends AnyWordSpecLike
     with Matchers
     with DefaultPlayMongoRepositorySupport[Lock] {

  private val timestampSupport = new CurrentTimestampSupport

  override protected val repository = new MongoLockRepository(mongoComponent, timestampSupport)

  private val lockId = "lockId"
  private val schedulerInterval = 5.seconds

  private val lockService = ScheduledLockService(repository, lockId, timestampSupport, schedulerInterval)

  "withLock" should {
    "execute the body if no lock is present" in {
      var counter = 0

      lockService.withLock {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 1
    }

    "prevent parallel executions across different instances" in {
      var counter = 0

      lockService.withLock {
        Future.successful(counter += 1)
      }.futureValue

      val anotherInstance = ScheduledLockService(repository, lockId, timestampSupport, schedulerInterval)

      anotherInstance.withLock {
        Future.successful(counter += 5) // shouldn't execute
      }.futureValue

      counter shouldBe 1
    }

    "prevent the body being executed more frequently than the scheduler interval" in {
      var counter = 0

      lockService.withLock {
        Future.successful(counter += 1)
      }.futureValue

      lockService.withLock {
        Future.successful(counter += 5) // shouldn't execute
      }.futureValue

      counter shouldBe 1

      Thread.sleep(schedulerInterval.toMillis)

      lockService.withLock {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 2
    }

    "prevent the body being executed when the previous execution has yet to finish" in {
      var counter = 0

      val running = lockService.withLock {
        Thread.sleep(schedulerInterval.toMillis + 2000)
        Future.successful(counter += 1)
      }

      Thread.sleep(schedulerInterval.toMillis)

      lockService.withLock {
        Future.successful(counter += 5) // shouldn't execute
      }.futureValue

      running.futureValue

      counter shouldBe 1
    }
  }

}
