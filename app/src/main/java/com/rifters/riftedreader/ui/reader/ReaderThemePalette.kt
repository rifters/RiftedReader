package com.rifters.riftedreader.ui.reader

import android.content.Context
import androidx.core.content.ContextCompat
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.preferences.ReaderTheme

data class ReaderThemePalette(
    val backgroundColor: Int,
    val textColor: Int
)

internal object ReaderThemePaletteResolver {

    fun resolve(context: Context, theme: ReaderTheme): ReaderThemePalette {
        val (backgroundRes, textRes) = when (theme) {
            ReaderTheme.DARK -> R.color.reader_background_dark to R.color.reader_text_dark
            ReaderTheme.SEPIA -> R.color.reader_background_sepia to R.color.reader_text_sepia
            ReaderTheme.BLACK -> R.color.reader_background_black to R.color.reader_text_black
            ReaderTheme.LIGHT -> R.color.reader_background_light to R.color.reader_text_light
        }
        return ReaderThemePalette(
            backgroundColor = ContextCompat.getColor(context, backgroundRes),
            textColor = ContextCompat.getColor(context, textRes)
        )
    }
}
