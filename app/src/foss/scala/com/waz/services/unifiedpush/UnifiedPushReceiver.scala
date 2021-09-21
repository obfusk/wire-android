/**
 * Wire
 * Copyright (C) 2021 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.services.unifiedpush

import android.content.Context
import android.widget.Toast
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.model.{Uid, UserId}
import com.waz.service.AccountsService.InForeground
import com.waz.service.push.PushService
import com.waz.service.push.PushService.{FetchFromIdle, SyncHistory}
import com.waz.service.{AccountsService, ZMessaging}
import com.waz.utils.JsonDecoder
import com.waz.zclient.WireApplication
import com.waz.zclient.core.logging.Logger.info
import com.waz.zclient.log.LogUI._
import com.waz.zclient.security.SecurityPolicyChecker
import com.wire.signals.CancellableFuture
import org.json.JSONObject
import org.unifiedpush.android.connector.{MessagingReceiver, MessagingReceiverHandler}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

// FIXME: pushSenderId?, stats? etc.
// See https://unifiedpush.org/developers/android/
object UnifiedPushReceiverHandler extends MessagingReceiverHandler with DerivedLogTag {
  import com.waz.threading.Threading.Implicits.Background

  lazy val accounts: AccountsService = ZMessaging.currentAccounts

  // FIXME: handle JSONException?
  // NB: message = full POST body of the push message
  override def onMessage(context: Context, message: String, instance: String): Unit =
    if (WireApplication.ensureInitialized()) processMessage(context, new JSONObject(message))

  // FIXME: WIP
  private def processMessage(context: Context, data: JSONObject): Unit = {
    implicit val _context: Context = context

    // FIXME: DEBUG
    info(TAG, s"Processing UnifiedPush message with data: ${data.toString()}")

    def callHandlerOrWait(userId: UserId, retry: Int = 0)(implicit maxRetries: Int = 3, delay: FiniteDuration = 250.millis): Unit =
      accounts.getZms(userId).foreach {
        case None if retry < maxRetries => CancellableFuture.delayed(delay)(callHandlerOrWait(userId, retry + 1))
        case None                       => warn(l"Couldn't instantiate zms instance")
        case Some(zms)                  => UPHandler(zms, data)
      }

    verbose(l"Processing UnifiedPush message with data: ${redactedString(data.toString())}")

    Option(ZMessaging.currentGlobal) match {
      case None =>
        warn(l"No ZMessaging global available - calling too early")
      case _ => SecurityPolicyChecker.runBackgroundSecurityChecklist().foreach {
        case true => Try(data.getString(UserKey)).toOption.map(UserId(_)) match {
          case None =>
            warn(l"User key missing msg: ${redactedString(UserKeyMissingMsg)}")
          case Some(account) =>
            callHandlerOrWait(account)
        }
        case false =>
      }
    }
  }

  // FIXME: register endpoint with backend
  override def onNewEndpoint(context: Context, endpoint: String, instance: String): Unit =
    // FIXME: DEBUG
    info(TAG, s"New UnifiedPush endpoint: ${endpoint}")

  // FIXME: use translatable resource
  override def onRegistrationFailed(context: Context, instance: String): Unit =
    Toast.makeText(context, "UnifiedPush registration failed", Toast.LENGTH_SHORT).show()

  // FIXME: use translatable resource
  override def onRegistrationRefused(context: Context, instance: String): Unit =
    Toast.makeText(context, "UnifiedPush registration refused", Toast.LENGTH_SHORT).show()

  // FIXME: do something!?
  // FIXME: use translatable resource
  override def onUnregistered(context: Context, instance: String): Unit =
    Toast.makeText(context, "UnifiedPush unregistered", Toast.LENGTH_SHORT).show()

  val TAG = "UnifiedPushReceiver"

  // FIXME
  val DataKey = "data"
  val UserKey = "user"
  val UserKeyMissingMsg = "Notification did not contain user key - discarding"

  class UPHandler(userId: UserId,
                  accounts: AccountsService,
                  push: PushService) extends DerivedLogTag {

    def handleMessage(data: JSONObject): Future[Unit] =
      Try(JsonDecoder.decodeUid('id)(data.getJSONObject(DataKey))).toOption match { case id =>
        if (id.isEmpty) warn(l"Unexpected notification, sync anyway")
        addNotificationToProcess(id)
      }

    // WARNING: see FCM
    private def addNotificationToProcess(nId: Option[Uid]): Future[Unit] =
      for {
        false <- accounts.accountState(userId).map(_ == InForeground).head
        _ <- push.syncNotifications(SyncHistory(FetchFromIdle(nId)))
      } yield {}
  }

  object UPHandler {
    private val handlers = scala.collection.mutable.HashMap[UserId, UPHandler]()

    def apply(zms: ZMessaging, data: JSONObject): Unit =
      handlers.getOrElseUpdate(zms.selfUserId, new UPHandler(zms.selfUserId, zms.accounts, zms.push))
        .handleMessage(data)
  }
}

class UnifiedPushReceiver extends MessagingReceiver(UnifiedPushReceiverHandler)
