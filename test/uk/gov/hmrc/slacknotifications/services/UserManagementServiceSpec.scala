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

import java.util.UUID

import net.sf.ehcache.CacheManager
import org.scalatest.concurrent.ScalaFutures
import play.api.cache.AsyncCacheApi
import play.api.cache.ehcache.EhCacheApi
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.{TeamDetails, UmpUser}
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.Future

class UserManagementServiceSpec extends UnitSpec with ScalaFutures {

  private implicit val hc = HeaderCarrier()
  private implicit val ec = ExecutionContext.Implicits.global

  "Service" should {
    "lookup LDAP username based on a Github handle using cached UMP response" in new Fixtures {
      val githubUsername    = "user-1"
      val githubUsernameUrl = s"https://github.com/$githubUsername"
      val ldapUsername      = "ldap-username"
      val service           = new UserManagementService(mockedUMPConnector, cacheApi)
      val umpUsers          = List(UmpUser(Some(githubUsernameUrl), Some(ldapUsername)))

      when(mockedUMPConnector.getAllUsers(any[HeaderCarrier]))
        .thenReturn(Future.successful(umpUsers))
        .andThenThrow(new RuntimeException("Caching was supposed to prevent more than 1 call"))

      (1 to 5).foreach { _ =>
        service.getLdapUsername(githubUsername).futureValue.get shouldBe ldapUsername
      }
    }

    "ldap lookup ignores case" in new Fixtures {
      val githubUsername    = "UserName"
      val githubUsernameUrl = "https://github.com/username"
      val ldapUsername      = "ldap-username"
      val service           = new UserManagementService(mockedUMPConnector, cacheApi)
      val umpUsers          = List(UmpUser(Some(githubUsernameUrl), Some(ldapUsername)))

      when(mockedUMPConnector.getAllUsers(any[HeaderCarrier]))
        .thenReturn(Future.successful(umpUsers))

      service.getLdapUsername(githubUsername).futureValue.get shouldBe ldapUsername
    }

    "lookup MDTP teams for a Github user" in new Fixtures {
      val githubUsername    = "user-1"
      val githubUsernameUrl = s"https://github.com/$githubUsername"
      val ldapUsername      = "ldap-username"
      val service           = new UserManagementService(mockedUMPConnector, cacheApi)
      val umpUsers          = List(UmpUser(Some(githubUsernameUrl), Some(ldapUsername)))
      val teams             = List(TeamDetails(slack = None, slackNotification = None, team = "n/a"))

      when(mockedUMPConnector.getAllUsers(any[HeaderCarrier]))
        .thenReturn(Future.successful(umpUsers))
      when(mockedUMPConnector.getTeamsForUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(teams))

      service.getTeamsForGithubUser(githubUsername).futureValue shouldBe teams
    }
  }

  private trait Fixtures {
    val mockedUMPConnector = mock[UserManagementConnector]
    val cacheApi: AsyncCacheApi = {
      val cacheManager = CacheManager.create()
      val cacheName    = UUID.randomUUID().toString
      cacheManager.addCache(cacheName)
      val cache = cacheManager.getCache(cacheName)
      new EhCacheApi(cache)
    }
  }
}
