package io.github.serg987.sudokueinkhtr

import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

object EinkOptimizations {

    // Desactiva les animacions del sistema (versió moderna)
    fun disableAnimations(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // Desactiva animacions de transicions
        window.decorView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    // Forçar refresc complet de pantalla (per Onyx Boox)
    fun forceFullRefresh(view: View) {
        try {
            val method = view.javaClass.getMethod("postInvalidate")
            method.invoke(view)
        } catch (e: Exception) {
            // Si no és un dispositiu Boox, ignorar
            view.postInvalidate()
        }
    }

    /**
     * True on an Onyx device whose per-app E-ink refresh mode is anything other than
     * Normal (Speed/A2/X/Regal). Only Normal-mode sessions are known clean against the
     * EPD-driver freeze the fast modes can trigger during pen use, so the UI shows a
     * warning whenever this returns true.
     *
     * The mode is the E-ink-center per-app setting and can change mid-session via the
     * floating ball — re-check on every resume, not once at startup. Device-verified
     * mapping (Boox Max3): Normal=NORMAL, Regal=REGAL, Speed=FAST_QUALITY, A2=FAST,
     * X=FAST_X. On non-Onyx devices (or if the hidden framework getter is missing and
     * the SDK falls back) this stays false — a hidden warning is the safe failure mode.
     */
    fun isNonNormalRefreshMode(): Boolean {
        val onyx = android.os.Build.MANUFACTURER.equals("ONYX", ignoreCase = true) ||
            android.os.Build.BRAND.equals("ONYX", ignoreCase = true)
        if (!onyx) return false
        return try {
            val mode = com.onyx.android.sdk.api.device.epd.EpdController.getAppScopeRefreshMode()
                ?: return false
            mode != com.onyx.android.sdk.api.device.epd.UpdateOption.NORMAL
        } catch (t: Throwable) {
            false
        }
    }

    // Optimització específica per Onyx Boox
    fun setOnyxRefreshMode(window: Window, mode: Int) {
        try {
            // Mode 0: Actualització normal
            // Mode 1: Actualització ràpida (A2 mode)
            // Mode 2: Actualització de qualitat (GC16)
            val decorView = window.decorView
            val method = decorView.javaClass.getMethod("setDefaultUpdateMode", Int::class.java)
            method.invoke(decorView, mode)
        } catch (e: Exception) {
            // No és un dispositiu Onyx o no suporta aquesta funció
        }
    }
}

@Composable
fun DisableCompositionAnimations() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        EinkOptimizations.forceFullRefresh(view)
        onDispose { }
    }
}
