/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
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
package com.waz.zclient.messages.parts

import android.view.ViewGroup
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.model.UserId
import com.wire.signals.Signal
import com.waz.utils.returning
import com.waz.zclient.common.views.ChatHeadView
import com.waz.zclient.log.LogUI._
import com.waz.zclient.messages.MessageViewFactory
import com.waz.zclient.{R, ViewHelper}
import com.waz.threading.Threading._

trait ChatheadsRecyclerView extends ViewGroup with ViewHelper with DerivedLogTag {
  val cache = inject[MessageViewFactory]
  val chatHeadResId = R.layout.message_member_chathead

  val users = Signal[Seq[UserId]]()

  users.onUi { ids =>
    verbose(l"user id: $ids")
    if (getChildCount > ids.length) {
      for (i <- ids.length until getChildCount) cache.recycle(getChildAt(i), chatHeadResId)
      removeViewsInLayout(ids.length, getChildCount - ids.length)
    }

    ids.zipWithIndex.foreach { case (id, index) =>
      val view =
        if (index < getChildCount) getChildAt(index).asInstanceOf[ChatHeadView]
        else returning(cache.get[ChatHeadView](chatHeadResId, this)) { addView }

      view.loadUser(id)
    }
  }
}
