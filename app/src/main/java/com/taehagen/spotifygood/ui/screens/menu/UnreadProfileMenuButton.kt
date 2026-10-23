package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
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
    Box {
        AvatarButton(user = user, onClick = onClick)
        if (unreadMessages > 0) {
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp)) {
                ChatUnreadBadge(unreadMessages)
            }
        }
    }
}
