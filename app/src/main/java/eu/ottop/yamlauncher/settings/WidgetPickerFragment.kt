package eu.ottop.yamlauncher.settings

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import com.google.android.material.textfield.TextInputEditText
import eu.ottop.yamlauncher.R
import eu.ottop.yamlauncher.utils.Logger
import eu.ottop.yamlauncher.utils.UIUtils
import eu.ottop.yamlauncher.views.WidgetStackView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Adds and removes app widgets for the home screen widget stack.
 *
 * Available widgets are grouped under a collapsible header per app. Rows show a
 * preview thumbnail and the widget's grid/dp size; previews are decoded only
 * for rows that are on screen (expanded or matching the search) and dropped
 * again when hidden, so memory stays proportional to what is visible.
 *
 * Adding follows the standard host flow: allocate an id, bind (the system asks
 * the user for permission the first time), run the widget's configure activity
 * if it has one, then persist the id. Any cancelled step releases the id again.
 */
class WidgetPickerFragment : PreferenceFragmentCompat(), TitleProvider {

    private class Row(val info: AppWidgetProviderInfo?, val label: String, val pref: Preference)
    private class Group(val pkg: String, val label: String, val header: AppGroupPreference, val rows: List<Row>)

    private lateinit var sharedPreferenceManager: SharedPreferenceManager
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var logger: Logger

    private var addedRows: List<Row> = emptyList()
    private var groups: List<Group> = emptyList()
    /** Package names whose group is open; searching shows every match regardless. */
    private val expanded = HashSet<String>()
    private var query = ""

    /** Id allocated for an add flow that has not finished yet. */
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val bindLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) configureOrSavePending() else discardPending()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        sharedPreferenceManager = SharedPreferenceManager(requireContext())
        appWidgetManager = AppWidgetManager.getInstance(requireContext())
        appWidgetHost = AppWidgetHost(requireContext(), WidgetStackView.HOST_ID)
        logger = Logger.getInstance(requireContext())
        pendingId = savedInstanceState?.getInt(STATE_PENDING_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        savedInstanceState?.getStringArrayList(STATE_EXPANDED)?.let(expanded::addAll)
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        rebuild()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val list = super.onCreateView(inflater, container, savedInstanceState)
        val root = inflater.inflate(R.layout.fragment_widget_picker, container, false) as LinearLayout
        root.addView(list, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val uiUtils = UIUtils(requireContext())
        uiUtils.setSettingsTextColors(view)
        val search = view.findViewById<TextInputEditText>(R.id.widgetSearch)
        uiUtils.setSearchAlignment(search)
        uiUtils.setSearchSize(search)
        search.doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            refreshVisibility()
        }
        // Text survives recreation but the listener above does not see it.
        query = search.text?.toString()?.trim().orEmpty()
        if (query.isNotEmpty()) refreshVisibility()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PENDING_ID, pendingId)
        outState.putStringArrayList(STATE_EXPANDED, ArrayList(expanded))
    }

    override fun getTitle(): String = getString(R.string.widget_stack_widgets)

    private fun rebuild() {
        val context = requireContext()
        val pm = context.packageManager
        val screen = preferenceScreen
        screen.removeAll()

        val ids = sharedPreferenceManager.getWidgetStackIds()
        addedRows = if (ids.isEmpty()) emptyList() else {
            val added = PreferenceCategory(context).apply { title = getString(R.string.widget_stack_added) }
            screen.addPreference(added)
            ids.map { id ->
                val info = appWidgetManager.getAppWidgetInfo(id)
                val label = info?.loadLabel(pm) ?: getString(R.string.widget_unavailable)
                widgetRow(info, label, R.string.widget_remove_summary) { removeWidget(id) }
                    .also { added.addPreference(it.pref) }
            }
        }

        val available = PreferenceCategory(context).apply { title = getString(R.string.widget_stack_available) }
        screen.addPreference(available)
        groups = appWidgetManager.installedProviders
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            .groupBy { it.provider.packageName }
            .map { (pkg, infos) -> Triple(pkg, appLabel(pkg), infos) }
            .sortedBy { it.second.lowercase() }
            .map { (pkg, appLabel, infos) ->
                val header = AppGroupPreference(context).apply {
                    title = appLabel
                    summary = resources.getQuantityString(R.plurals.widget_stack_count, infos.size, infos.size)
                    icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                    setOnPreferenceClickListener {
                        if (!this@WidgetPickerFragment.expanded.add(pkg)) this@WidgetPickerFragment.expanded.remove(pkg)
                        refreshVisibility()
                        true
                    }
                }
                available.addPreference(header)
                val rows = infos
                    .map { it to it.loadLabel(pm) }
                    .sortedBy { it.second.lowercase() }
                    .map { (info, label) ->
                        widgetRow(info, label, null) { addWidget(info) }.also { available.addPreference(it.pref) }
                    }
                Group(pkg, appLabel, header, rows)
            }
        refreshVisibility()
    }

    private fun widgetRow(info: AppWidgetProviderInfo?, label: String, hint: Int?, onClick: () -> Unit): Row {
        val pref = Preference(requireContext()).apply {
            layoutResource = R.layout.preference_widget
            isIconSpaceReserved = true
            title = label
            summary = listOfNotNull(info?.let(::sizeSummary), hint?.let(::getString)).joinToString("\n")
            setOnPreferenceClickListener {
                onClick()
                true
            }
        }
        return Row(info, label, pref)
    }

    /** Applies collapse state and search filter, then loads previews for rows that just became visible. */
    private fun refreshVisibility() {
        val searching = query.isNotEmpty()
        val toLoad = addedRows.filter { it.pref.icon == null }.toMutableList()
        for (group in groups) {
            val appMatch = group.label.contains(query, ignoreCase = true)
            val shown = group.rows.filter { !searching || appMatch || it.label.contains(query, ignoreCase = true) }
            val open = searching || group.pkg in expanded
            group.header.isVisible = shown.isNotEmpty()
            group.header.expanded = open
            for (row in group.rows) {
                val visible = open && row in shown
                row.pref.isVisible = visible
                if (!visible) row.pref.icon = null else if (row.pref.icon == null) toLoad += row
            }
        }
        if (toLoad.isEmpty()) return
        lifecycleScope.launch {
            val previews = withContext(Dispatchers.IO) { toLoad.map { row -> row.info?.let(::preview) } }
            toLoad.forEachIndexed { i, row -> if (row.pref.isVisible) row.pref.icon = previews[i] }
        }
    }

    /** Provider preview scaled to the row thumbnail so only a small bitmap is kept per visible row. */
    private fun preview(info: AppWidgetProviderInfo): Drawable? {
        val context = requireContext()
        // ponytail: S+ widgets that ship only previewLayout fall back to the provider icon;
        // a live render would need an AppWidgetHostView per row.
        val source = runCatching { info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0) }
            .getOrNull() ?: return null
        val w = source.intrinsicWidth
        val h = source.intrinsicHeight
        if (w <= 0 || h <= 0) return source
        val scale = minOf(
            resources.getDimension(R.dimen.widget_preview_width) / w,
            resources.getDimension(R.dimen.widget_preview_height) / h,
            1f,
        )
        return source.toBitmap((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            .toDrawable(resources)
    }

    private fun sizeSummary(info: AppWidgetProviderInfo): String {
        val density = resources.displayMetrics.density
        val widthDp = (info.minWidth / density).roundToInt()
        val heightDp = (info.minHeight / density).roundToInt()
        val targetCells = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0
        val cols = if (targetCells) info.targetCellWidth else cells(widthDp)
        val rows = if (targetCells) info.targetCellHeight else cells(heightDp)
        return getString(R.string.widget_size_summary, cols, rows, widthDp, heightDp)
    }

    /** Inverse of the platform grid rule "size = 70 × n − 30 dp". */
    private fun cells(dp: Int): Int = ceil((dp + 30) / 70f).toInt().coerceAtLeast(1)

    private fun appLabel(pkg: String): String {
        val pm = requireContext().packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    /** Collapsible app header: a plain preference whose chevron and state description follow [expanded]. */
    private class AppGroupPreference(context: Context) : Preference(context) {
        var expanded = false
            set(value) {
                if (field == value) return
                field = value
                notifyChanged()
            }

        init {
            widgetLayoutResource = R.layout.preference_widget_group_indicator
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            holder.findViewById(R.id.expandIndicator)?.rotation = if (expanded) 180f else 0f
            ViewCompat.setStateDescription(
                holder.itemView,
                context.getString(if (expanded) R.string.widget_group_expanded else R.string.widget_group_collapsed),
            )
        }
    }

    private fun addWidget(info: AppWidgetProviderInfo) {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) return
        val bound = try {
            pendingId = appWidgetHost.allocateAppWidgetId()
            appWidgetManager.bindAppWidgetIdIfAllowed(pendingId, info.profile, info.provider, null)
        } catch (e: Exception) {
            logger.w("WidgetPickerFragment", "Cannot bind ${info.provider}: ${e.message}")
            failPending()
            return
        }
        if (bound) {
            configureOrSavePending()
        } else {
            bindLauncher.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            )
        }
    }

    private fun configureOrSavePending() {
        val info = appWidgetManager.getAppWidgetInfo(pendingId)
        val configurationOptional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info != null &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info?.configure == null || configurationOptional) {
            savePending()
            return
        }
        try {
            // Only the Activity result path exists for this; SettingsActivity forwards it back.
            appWidgetHost.startAppWidgetConfigureActivityForResult(requireActivity(), pendingId, 0, REQUEST_CONFIGURE, null)
        } catch (e: Exception) {
            logger.w("WidgetPickerFragment", "Cannot configure ${info.provider}: ${e.message}")
            failPending()
        }
    }

    /** Result of the widget's configure activity, forwarded by [SettingsActivity]. */
    fun onConfigureResult(requestCode: Int, resultCode: Int) {
        if (requestCode != REQUEST_CONFIGURE || pendingId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (resultCode == Activity.RESULT_OK) savePending() else discardPending()
    }

    private fun savePending() {
        sharedPreferenceManager.setWidgetStackIds(sharedPreferenceManager.getWidgetStackIds() + pendingId)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        rebuild()
    }

    private fun discardPending() {
        if (pendingId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        appWidgetHost.deleteAppWidgetId(pendingId)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun failPending() {
        discardPending()
        Toast.makeText(requireContext(), R.string.widget_add_failed, Toast.LENGTH_SHORT).show()
    }

    private fun removeWidget(id: Int) {
        appWidgetHost.deleteAppWidgetId(id)
        sharedPreferenceManager.setWidgetStackIds(sharedPreferenceManager.getWidgetStackIds() - id)
        rebuild()
    }

    companion object {
        const val REQUEST_CONFIGURE = 0x5741
        private const val STATE_PENDING_ID = "pendingWidgetId"
        private const val STATE_EXPANDED = "expandedWidgetApps"
    }
}
