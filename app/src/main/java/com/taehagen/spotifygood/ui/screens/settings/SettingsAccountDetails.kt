package com.taehagen.spotifygood.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.model.User

// The Spotify Good user model does not contain an email address, billing cadence or street
// address. Those fields must remain web links instead of guessed or hard-coded user data.
private const val ACCOUNT_PROFILE = "https://www.spotify.com/account/profile/"
private const val ACCOUNT_OVERVIEW = "https://www.spotify.com/account/overview/"
private const val CLOSE_ACCOUNT_HELP =
    "https://support.spotify.com/us/article/how-can-i-close-my-spotify-account/"

/** Settings subpage, built as separate lazy items so search can highlight the actual row. */
internal fun LazyListScope.accountSettingsRows(user: User?, openWeb: (String) -> Unit) {
    item(key = "account_details_header") { AccountSectionHeading("Account details") }
    item(key = "account_username") {
        AccountInfoRow("Username", user?.username?.takeIf(String::isNotBlank) ?: "Unavailable")
    }
    item(key = "account_email") {
        AccountInfoRow("Email", "View or update your email on Spotify.com", true) {
            openWeb(ACCOUNT_PROFILE)
        }
    }
    item(key = "account_address") {
        AccountInfoRow("Address", "View or change your address on Spotify.com", true) {
            openWeb(ACCOUNT_OVERVIEW)
        }
    }
    item(key = "account_overview") {
        AccountInfoRow("Account overview", "View more account details on the web", true) {
            openWeb(ACCOUNT_OVERVIEW)
        }
    }
    item(key = "account_plan_header") {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AccountSectionHeading("Your plan")
    }
    item(key = "account_plan") {
        AccountPlanRow(user) { openWeb(ACCOUNT_OVERVIEW) }
    }
    item(key = "account_close") {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
        val linkStyle = TextLinkStyles(
            SpanStyle(color = bodyColor, textDecoration = TextDecoration.Underline),
        )
        Text(
            text = buildAnnotatedString {
                append("To delete your data permanently, ")
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "close_account",
                        styles = linkStyle,
                        linkInteractionListener = { openWeb(CLOSE_ACCOUNT_HELP) },
                    ),
                ) { append("close your account") }
                append(".")
            },
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 30.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = bodyColor,
        )
    }
}

@Composable
private fun AccountSectionHeading(title: String) {
    Text(title,
        modifier = Modifier.fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun AccountInfoRow(
    title: String,
    detail: String,
    external: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val modifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else Modifier
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (external) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.OpenInNew, contentDescription = "Opens browser",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AccountPlanRow(user: User?, onClick: () -> Unit) {
    val premium = user?.product.equals("premium", ignoreCase = true)
    val planName = if (premium) {
        when (user?.planType?.lowercase()?.removePrefix("premium_")) {
            "family" -> "Premium Family"
            "duo" -> "Premium Duo"
            "student" -> "Premium Student"
            "individual" -> "Premium Individual"
            else -> "Spotify Premium"
        }
    } else if (user?.product.equals("free", ignoreCase = true)) {
        "Spotify Free"
    } else {
        "Plan information unavailable"
    }
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(8.dp)) {
            Icon(Icons.Rounded.WorkspacePremium, contentDescription = null,
                tint = if (premium) Color(0xFFD8A3FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp).size(36.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(planName, style = MaterialTheme.typography.titleLarge)
            Text(if (premium) "View billing and renewal details on Spotify.com" else
                "View subscription details on Spotify.com",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.OpenInNew, contentDescription = "Opens browser",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
