package com.taehagen.spotifygood.ui.messaging

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.giphy.sdk.core.models.Media
import com.giphy.sdk.ui.GPHContentType
import com.giphy.sdk.core.models.enums.RatingType
import com.giphy.sdk.ui.GPHSettings
import com.giphy.sdk.ui.views.GiphyDialogFragment
import com.taehagen.spotifygood.BuildConfig

/** GIPHY owns searching and browsing; only its provider ID/rating return to chat. */
class GiphyMessagePickerActivity : FragmentActivity(), GiphyDialogFragment.GifSelectionListener {
    companion object {
        const val EXTRA_MEDIA_ID = "spotifygood.giphy.id"
        const val EXTRA_RATING = "spotifygood.giphy.rating"
        private const val DIALOG_TAG = "giphy-picker"

        /** Shared validator for both the embedded grid and the full SDK dialog. */
        fun supportedSelection(media: Media): Pair<String, Int>? {
            val id = media.id.orEmpty()
            val rawRating = runCatching {
                media.javaClass.methods.firstOrNull {
                    it.name == "getRating" && it.parameterCount == 0
                }?.invoke(media)?.toString()?.trim()?.lowercase()
            }.getOrNull()
            val rating = when (rawRating) {
                "y" -> 1
                "g" -> 2
                "pg" -> 3
                "pg13", "pg-13" -> 4
                "r" -> 5
                else -> null
            } ?: return null
            if (id.length !in 3..128 || !id.all {
                (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9') || it == '-' || it == '_'
            }) return null
            return id to rating
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.GIPHY_SDK_KEY.isBlank()) {
            Toast.makeText(this,
                "GIPHY key required. Add giphySdkKey to local.properties and rebuild.",
                Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        if (savedInstanceState == null) {
            val settings = GPHSettings().apply {
                mediaTypeConfig = arrayOf(GPHContentType.gif)
                selectedContentType = GPHContentType.gif
                rating = RatingType.pg13
            }
            GiphyDialogFragment.newInstance(settings, BuildConfig.GIPHY_SDK_KEY).apply {
                gifSelectionListener = this@GiphyMessagePickerActivity
            }.show(supportFragmentManager, DIALOG_TAG)
        } else {
            (supportFragmentManager.findFragmentByTag(DIALOG_TAG) as? GiphyDialogFragment)
                ?.gifSelectionListener = this
        }
    }

    override fun onGifSelected(media: Media, searchTerm: String?, selectedContentType: GPHContentType) {
        if (selectedContentType != GPHContentType.gif) return
        val selection = supportedSelection(media)
        if (selection == null) {
            Toast.makeText(this, "GIPHY didn't supply a supported GIF ID and rating", Toast.LENGTH_LONG).show()
            return
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_MEDIA_ID, selection.first)
            .putExtra(EXTRA_RATING, selection.second))
        finish()
    }

    override fun onDismissed(selectedContentType: GPHContentType) { finish() }
    override fun didSearchTerm(term: String) = Unit
}
