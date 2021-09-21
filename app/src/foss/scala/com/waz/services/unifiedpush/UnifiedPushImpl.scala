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

import android.app.AlertDialog
import android.content.{Context, DialogInterface}
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.{TextView, Toast}
import com.waz.utils.returning
import com.waz.utils.wrappers.UnifiedPushApi
import com.wire.signals.{Signal, SourceSignal}
import org.unifiedpush.android.connector.Registration

import scala.collection.JavaConverters._
import scala.util.Try

// FIXME: allow disabling UP, "don't ask again"
// FIXME: handle unregister, better UI, ...
class UnifiedPushApiImpl extends UnifiedPushApi {
  override val isUnifiedPushAvailable: SourceSignal[Boolean] = Signal()

  // FIXME: use translatable resources
  override def initUnifiedPush(context: Context): Unit = {
    val up = new Registration
    if (up.getDistributor(context).nonEmpty) {
      up.registerApp(context)
      isUnifiedPushAvailable ! true
    } else {
      def enable(distributor: String): Unit = {
        up.saveDistributor(context, distributor)
        up.registerApp(context)
        isUnifiedPushAvailable ! true
        Toast.makeText(context, "UnifiedPush enabled", Toast.LENGTH_SHORT).show() // FIXME
      }
      val distributors = up.getDistributors(context)
      distributors.size match {
        case 0 =>
          val s = new SpannableString("To get push notifications, it is recommended " +
                                      "(but not required) to install a UnifiedPush distributor.\n" +
                                      "For more information, see https://unifiedpush.org/")
          Linkify.addLinks(s, Linkify.WEB_URLS)
          val message = new TextView(context)
          message.setText(s)
          message.setMovementMethod(LinkMovementMethod.getInstance())
          message.setPadding(32, 32, 32, 32)
          new AlertDialog.Builder(context).setTitle("No UnifiedPush distributor found").setView(message).show()
          isUnifiedPushAvailable ! false
        case 1 =>
          enable(distributors.get(0))
        case _ =>
          val distributorNames = distributors.asScala.map { d =>
            Try(context.getPackageManager match { case pm => pm.getApplicationLabel(pm.getApplicationInfo(d, 0)) })
              .toOption.getOrElse(d)
          }
          new AlertDialog.Builder(context)
            .setTitle("Choose a UnifiedPush distributor")
            .setItems(distributorNames.toArray, new DialogInterface.OnClickListener {
              override def onClick(dialog: DialogInterface, which: Int): Unit =
                enable(distributors.get(which))
            })
            .setOnDismissListener(new DialogInterface.OnDismissListener {
              override def onDismiss(dialog: DialogInterface): Unit =
                isUnifiedPushAvailable ! false
            })
            .create().show()
      }
    }
  }
}

object UnifiedPushApiImpl {
  private var instance = Option.empty[UnifiedPushApiImpl]

  def apply(): UnifiedPushApiImpl = synchronized {
    instance match {
      case Some(api) => api
      case None => returning(new UnifiedPushApiImpl){ api: UnifiedPushApiImpl => instance = Some(api) }
    }
  }
}
