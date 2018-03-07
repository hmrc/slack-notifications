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

import java.util.UUID
import net.sf.ehcache.CacheManager
import org.mockito.Mockito.when
import org.mockito.Matchers.any
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{Matchers, WordSpec}
import play.api.cache.{CacheApi, EhCacheApi}
import scala.concurrent.Future
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.{TeamDetails, UmpUser}
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

class UserManagementServiceSpec extends WordSpec with Matchers with MockitoSugar with ScalaFutures {

  implicit val hc = HeaderCarrier()

  "Service" should {
    "lookup LDAP username based on a Github handle using cached UMP response" in new Fixtures {
      val githubUsername    = "user-1"
      val githubUsernameUrl = s"https://github.com/$githubUsername"
      val ldapUsername      = "ldap-username"
      val service           = new UserManagementService(mockedUMPConnector, cacheApi)
      val umpUsers          = List(UmpUser(Some(githubUsernameUrl), Some(ldapUsername)))

      when(mockedUMPConnector.getAllUsers(any()))
        .thenReturn(Future(umpUsers))
        .thenThrow(new RuntimeException("Caching was supposed to prevent more than 1 call"))

      (1 to 5).foreach { _ =>
        service.getLdapUsername(githubUsername).futureValue.get shouldBe ldapUsername
      }
    }

    "lookup MDTP teams for a Github user" in new Fixtures {
      val githubUsername    = "user-1"
      val githubUsernameUrl = s"https://github.com/$githubUsername"
      val ldapUsername      = "ldap-username"
      val service           = new UserManagementService(mockedUMPConnector, cacheApi)
      val umpUsers          = List(UmpUser(Some(githubUsernameUrl), Some(ldapUsername)))
      val teams             = List(TeamDetails(slack = None, team = "n/a"))

      when(mockedUMPConnector.getAllUsers(any())).thenReturn(Future.successful(umpUsers))
      when(mockedUMPConnector.getTeamsForUser(any())(any())).thenReturn(Future(teams))

      service.getTeamsForGithubUser(githubUsername).futureValue shouldBe teams
    }

  }

  trait Fixtures {
    val mockedUMPConnector = mock[UserManagementConnector]
    val cacheApi: CacheApi = {
      val cacheManager = CacheManager.create()
      val cacheName    = UUID.randomUUID().toString
      cacheManager.addCache(cacheName)
      val cache = cacheManager.getCache(cacheName)
      new EhCacheApi(cache)
    }
  }

}
