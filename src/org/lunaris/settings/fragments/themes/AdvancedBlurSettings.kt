/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lunaris.settings.fragments.themes

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import com.android.internal.graphics.AdvancedBlurConfig
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.search.SearchIndexable
import com.android.settingslib.search.SearchIndexableRaw
import org.lunaris.settings.utils.SystemUtils
import java.util.concurrent.Executors

@SearchIndexable
class AdvancedBlurSettings : SettingsPreferenceFragment() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var loading = false
    private var generation = 0
    private var pendingUri: Uri? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPreset(uri)
    }
    private val observer = Runnable { if (isResumed) updateControls() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.advanced_blur_settings)
        findPreference<Preference>("advanced_blur_load")?.setOnPreferenceClickListener {
            try {
                picker.launch(arrayOf(
                    "application/zip", "application/x-zip-compressed", "application/octet-stream",
                ))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.advanced_blur_picker_missing, Toast.LENGTH_LONG).show()
            }
            true
        }
        findPreference<Preference>("advanced_blur_remove")?.setOnPreferenceClickListener {
            generation++
            loading = false
            pendingUri = null
            if (!AdvancedBlurConfig.clear(requireContext())) {
                Toast.makeText(requireContext(), R.string.advanced_blur_save_failed, Toast.LENGTH_LONG).show()
            } else {
                SystemUtils.showSystemUiRestartDialog(requireContext())
            }
            updateControls()
            true
        }
        savedInstanceState?.getString(PENDING_URI)?.let { importPreset(Uri.parse(it)) }
    }

    override fun onResume() {
        super.onResume()
        AdvancedBlurConfig.addListener(requireContext(), observer)
        updateControls()
    }

    override fun onPause() {
        AdvancedBlurConfig.removeListener(observer)
        super.onPause()
    }

    override fun onDestroy() {
        generation++
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingUri?.let { outState.putString(PENDING_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun importPreset(uri: Uri) {
        val context = requireContext().applicationContext
        val user = AdvancedBlurConfig.targetUserId(context)
        val targetContext = if (user == context.userId) context
            else context.createContextAsUser(UserHandle.of(user), 0)
        val request = ++generation
        pendingUri = uri
        loading = true
        updateControls()
        executor.execute {
            val result = runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error(context.getString(R.string.advanced_blur_read_failed))
                input.use { AdvancedBlurConfig.readZip(it) }.also {
                    it.validate(targetContext, targetContext.getColor(com.android.internal.R.color.surface_effect_0))
                }
            }
            handler.post {
                if (request != generation) return@post
                loading = false
                pendingUri = null
                if (!isAdded) return@post
                if (user != AdvancedBlurConfig.targetUserId(context)) {
                    updateControls()
                    return@post
                }
                result.fold(
                    onSuccess = { preset ->
                        val saved = runCatching { preset.save(targetContext) }.getOrDefault(false)
                        if (!saved) {
                            Toast.makeText(context, R.string.advanced_blur_save_failed, Toast.LENGTH_LONG).show()
                        } else {
                            SystemUtils.showSystemUiRestartDialog(requireContext())
                        }
                    },
                    onFailure = { error ->
                        Toast.makeText(context, getString(R.string.advanced_blur_import_failed,
                            error.message?.take(240).orEmpty()), Toast.LENGTH_LONG).show()
                    },
                )
                updateControls()
            }
        }
    }

    private fun updateControls() {
        if (!isAdded) return
        val preset = AdvancedBlurConfig.current(requireContext())
        findPreference<Preference>("advanced_blur_active")?.summary = if (preset == null) {
            getString(R.string.advanced_blur_stock)
        } else {
            listOf(preset.name, preset.author, preset.description).filter { it.isNotBlank() }.joinToString("\n")
        }
        findPreference<Preference>("advanced_blur_load")?.apply {
            isEnabled = !loading
            summary = getString(if (loading) R.string.advanced_blur_loading else R.string.advanced_blur_load_summary)
        }
        findPreference<Preference>("advanced_blur_remove")?.isEnabled =
            !loading && !AdvancedBlurConfig.stored(requireContext()).isNullOrEmpty()
    }

    override fun getMetricsCategory(): Int = MetricsEvent.LUNARIS

    companion object {
        private const val PENDING_URI = "advanced_blur_pending_uri"
        private const val SEARCH_KEYWORD = "Dead Hawk"
        @JvmField
        val SEARCH_INDEX_DATA_PROVIDER = object : BaseSearchIndexProvider() {
            override fun getRawDataToIndex(context: Context, enabled: Boolean): List<SearchIndexableRaw> =
                listOf(SearchIndexableRaw(context).apply {
                    key = "advanced_blur_config"
                    title = SEARCH_KEYWORD
                    screenTitle = SEARCH_KEYWORD
                    keywords = SEARCH_KEYWORD
                    className = AdvancedBlurSettings::class.java.name
                })
        }
    }
}
