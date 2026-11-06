package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.ui.screens.library.AvatarButton

/** The Home/Library menu launcher shares the drawer's unread-contribution count.
 * Keep the existing avatar button as the tap target; the badge is decorative. */
@Composable
internal fun UnreadProfileMenuButton(user: User?, onClick: () -> Unit) {
    val unreadMessages = ChatUnreadStates.counts.values.sum()
    val unreadUpdates = UpdatesUnreadState.unreadCount
    Box {
        AvatarButton(user = user, onClick = onClick)
        // The updates badge is distinct from the Messages count. Both can be visible.
        if (unreadUpdates > 0) {
            Surface(
                color = Color(0xFF2188FF),
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp).size(11.dp),
            ) {}
        }
        if (unreadMessages > 0) {
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp)) {
                ChatUnreadBadge(unreadMessages)
            }
        }
    }
}
