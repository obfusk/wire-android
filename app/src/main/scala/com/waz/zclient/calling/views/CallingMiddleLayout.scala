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
package com.waz.zclient.calling.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.waz.service.call.CallInfo.CallState.{OtherCalling, SelfConnected}
import com.wire.signals.{EventStream, Signal}
import com.waz.zclient.calling.controllers.CallController
import com.waz.zclient.common.views.ChatHeadView
import com.waz.zclient.utils.ContextUtils.getDimenPx
import com.waz.zclient.utils.{ContextUtils, RichView}
import com.waz.zclient.{BuildConfig, R, ViewHelper}
import com.waz.threading.Threading._

class CallingMiddleLayout(val context: Context, val attrs: AttributeSet, val defStyleAttr: Int)
  extends FrameLayout(context, attrs, defStyleAttr) with ViewHelper {
  import CallingMiddleLayout.CallDisplay

  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) =  this(context, null)

  inflate(R.layout.calling_middle_layout, this)

  private lazy val controller   = inject[CallController]
  private lazy val chathead     = findById[ChatHeadView](R.id.call_chathead)
  private lazy val callParticipantsView = findById[CallParticipantsView](R.id.call_participants)

  lazy val onShowAllClicked: EventStream[Unit] = callParticipantsView.onShowAllClicked

  Signal.zip(controller.callStateCollapseJoin, controller.isVideoCall, controller.isGroupCall).map {
    case (_,             false, false) => CallDisplay.Chathead
    case (OtherCalling,  false, true)  => CallDisplay.Chathead
    case (SelfConnected, _,     true)  => CallDisplay.Participants
    case _                             => CallDisplay.Empty
  }.onUi { display =>
    chathead.setVisible(display == CallDisplay.Chathead && !ContextUtils.isInLandscape)
    callParticipantsView.setVisible(display == CallDisplay.Participants)
  }

  controller.memberForPicture.onUi {
    case Some(uId) => chathead.loadUser(uId)
    case _         => chathead.clearUser()
  }

  override def onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int): Unit = {
    super.onLayout(changed, l, t, r, b)
    if (changed) callParticipantsView.setMaxRows((b - t) / getDimenPx(R.dimen.user_row_height))
    if (BuildConfig.LARGE_VIDEO_CONFERENCE_CALLS) {
      callParticipantsView.hideParticipants()
    }
  }
}

object CallingMiddleLayout {
  object CallDisplay {
    sealed trait CallDisplay
    case object Chathead extends CallDisplay
    case object Participants extends CallDisplay
    case object Empty extends CallDisplay
  }
}
