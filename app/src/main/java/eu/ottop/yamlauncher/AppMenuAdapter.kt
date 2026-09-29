package eu.ottop.yamlauncher

import android.annotation.SuppressLint
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import eu.ottop.yamlauncher.databinding.ActivityMainBinding
import eu.ottop.yamlauncher.settings.AppFolder
import eu.ottop.yamlauncher.settings.SharedPreferenceManager
import eu.ottop.yamlauncher.utils.AppNameResolver
import eu.ottop.yamlauncher.utils.AppUtils
import eu.ottop.yamlauncher.utils.Logger
import eu.ottop.yamlauncher.utils.UIUtils

/**
 * RecyclerView adapter for displaying installed apps in the app menu.
 * Handles app launching, shortcut assignment, and renaming.
 *
 * Features:
 * - DiffUtil for efficient list updates
 * - Pinned apps display with special icons
 * - Work profile indicator
 * - Shortcut selection mode
 * - Accessibility actions
 */
private sealed interface DrawerRow {
    data class Header(@StringRes val title: Int) : DrawerRow
    data class App(val entry: Triple<LauncherActivityInfo, UserHandle, Int>, val pinned: Boolean) : DrawerRow
    data class Folder(val folder: AppFolder) : DrawerRow
}

private class DrawerRowDiffCallback(
    private val oldList: List<DrawerRow>,
    private val newList: List<DrawerRow>
) : DiffUtil.Callback() {

    override fun getOldListSize() = oldList.size
    override fun getNewListSize() = newList.size

    /** Apps match on component name and profile index, folders on their stable id. */
    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
        val old = oldList[oldPos]
        val new = newList[newPos]
        return when (old) {
            is DrawerRow.Header -> new is DrawerRow.Header && old.title == new.title
            is DrawerRow.App -> new is DrawerRow.App &&
                old.entry.first.componentName == new.entry.first.componentName &&
                old.entry.third == new.entry.third
            is DrawerRow.Folder -> new is DrawerRow.Folder && old.folder.id == new.folder.id
        }
    }

    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
        val old = oldList[oldPos]
        val new = newList[newPos]
        return when (old) {
            is DrawerRow.Header -> true
            is DrawerRow.App -> new is DrawerRow.App && old.pinned == new.pinned &&
                old.entry.first.label == new.entry.first.label
            is DrawerRow.Folder -> new is DrawerRow.Folder && old.folder.name == new.folder.name
        }
    }
}

/**
 * Adapter for displaying apps in the app menu.
 * Supports multiple interaction modes:
 * - Normal: Click to launch, long-press for actions
 * - Shortcut selection: Click to assign to shortcut slot
 *
 * Outside search the list is split into Pinned, Folders and All apps sections, each with a
 * header only when it has rows. Search results are a flat list of apps.
 */
class AppMenuAdapter(
    private val activity: MainActivity,
    binding: ActivityMainBinding,
    private var apps: List<Triple<LauncherActivityInfo, UserHandle, Int>>,
    private val itemClickListener: OnItemClickListener,
    private val shortcutListener: OnShortcutListener,
    private val itemLongClickListener: OnItemLongClickListener,
    private val folderListener: OnFolderListener,
    launcherApps: LauncherApps
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // When set, clicking an app assigns it to this shortcut slot
    var shortcutIndex: Int = 0
    var shortcutTextView: TextView? = null

    private val sharedPreferenceManager = SharedPreferenceManager(activity)
    private val uiUtils = UIUtils(activity)
    private val appUtils = AppUtils(activity, launcherApps)
    private val logger = Logger.getInstance(activity)

    private var folders: List<AppFolder> = emptyList()
    private var isSectioned = true
    private var rows: List<DrawerRow> = emptyList()
    // Adapter position of each entry in [apps], since headers and folders sit between them.
    private var appRowPositions = IntArray(0)

    private var cachedTextColor: Int = sharedPreferenceManager.getTextColor()
    private var cachedTextShadowEnabled: Boolean = sharedPreferenceManager.isTextShadowEnabled()
    private var cachedContactsEnabled: Boolean = sharedPreferenceManager.areContactsEnabled()
    private var cachedAlignment: String? = sharedPreferenceManager.getAppAlignment()
    private var cachedSize: String? = sharedPreferenceManager.getAppSize()
    private var cachedSpacing: Int? = sharedPreferenceManager.getAppSpacing()
    private var cachedStrictClick: Boolean = sharedPreferenceManager.isAppStrictClickEnabled()
    private var cachedTypeface: Typeface? = uiUtils.resolveTypeface()

    private val drawableEmpty: Drawable? = ResourcesCompat.getDrawable(activity.resources, R.drawable.ic_empty, null)
    private val drawablePinFilled: Drawable? = ResourcesCompat.getDrawable(activity.resources, R.drawable.keep_filled_15px, null)
    private val drawablePin: Drawable? = ResourcesCompat.getDrawable(activity.resources, R.drawable.keep_15px, null)
    private val drawableWork: Drawable? = ResourcesCompat.getDrawable(activity.resources, R.drawable.ic_work_app, null)
    // Tinted once here rather than per bind; every folder row shares this instance.
    private val drawableFolder: Drawable? = ResourcesCompat.getDrawable(activity.resources, R.drawable.folder_15px, null)
        ?.mutate()?.apply { setTint(cachedTextColor) }

    init {
        setRows(buildRows())
    }

    fun onPreferencesChanged() {
        drawableFolder?.setTint(sharedPreferenceManager.getTextColor())
        cachedTextColor = sharedPreferenceManager.getTextColor()
        cachedTextShadowEnabled = sharedPreferenceManager.isTextShadowEnabled()
        cachedContactsEnabled = sharedPreferenceManager.areContactsEnabled()
        cachedAlignment = sharedPreferenceManager.getAppAlignment()
        cachedSize = sharedPreferenceManager.getAppSize()
        cachedSpacing = sharedPreferenceManager.getAppSpacing()
        cachedStrictClick = sharedPreferenceManager.isAppStrictClickEnabled()
        cachedTypeface = uiUtils.resolveTypeface()
        notifyDataSetChanged()
    }

    // ============================================
    // Listener Interfaces
    // ============================================

    /** Called when user clicks an app to launch it */
    interface OnItemClickListener {
        fun onItemClick(appInfo: LauncherActivityInfo, userHandle: UserHandle)
    }

    /** Called when user selects an app for a shortcut */
    interface OnShortcutListener {
        fun onShortcut(
            appInfo: LauncherActivityInfo,
            userHandle: UserHandle,
            textView: TextView,
            userProfile: Int,
            shortcutView: TextView,
            shortcutIndex: Int
        )
    }

    /** Called when user long-presses an app */
    interface OnItemLongClickListener {
        fun onItemLongClick(
            appInfo: LauncherActivityInfo,
            userHandle: UserHandle,
            userProfile: Int
        )
    }

    /** Folder row interactions; shortcut mode assigns the folder to a home slot instead of opening it. */
    interface OnFolderListener {
        fun onFolderClick(folder: AppFolder)
        fun onFolderLongClick(folder: AppFolder)
        fun onFolderShortcut(folder: AppFolder, shortcutView: TextView, shortcutIndex: Int)
    }

    // ============================================
    // ViewHolder
    // ============================================

    class HeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Container for the list item
        val listItem: FrameLayout = itemView.findViewById(R.id.listItem)
        // App name display
        val textView: TextView = listItem.findViewById(R.id.appName)
        // Rename input layout (hidden by default)
        val editView: LinearLayout = listItem.findViewById(R.id.renameView)
        // Text field in rename layout
        val editText: TextInputEditText = editView.findViewById(R.id.appNameEdit)

        init {
            // Strict click mode: only trigger click when touching actual text bounds
            textView.setOnTouchListener { _, event ->
                if (!cachedStrictClick || shortcutTextView != null || event.action != MotionEvent.ACTION_UP) {
                    return@setOnTouchListener false
                }
                val layout = textView.layout ?: return@setOnTouchListener false
                val line = layout.getLineForVertical(event.y.toInt())
                val lineBounds = Rect()
                layout.getLineBounds(line, lineBounds)
                val compoundPaddingLeft = textView.compoundPaddingLeft
                val touchX = event.x.toInt() - compoundPaddingLeft
                val touchY = event.y.toInt()
                if (touchX < lineBounds.left || touchX > lineBounds.right ||
                    touchY < lineBounds.top || touchY > lineBounds.bottom) {
                    return@setOnTouchListener true
                }
                false
            }

            // Single tap behavior depends on mode
            textView.setOnClickListener {
                val localShortcut = shortcutTextView
                when (val row = rows.getOrNull(bindingAdapterPosition)) {
                    is DrawerRow.Folder -> {
                        if (localShortcut != null) {
                            folderListener.onFolderShortcut(row.folder, localShortcut, shortcutIndex)
                        } else {
                            folderListener.onFolderClick(row.folder)
                        }
                    }
                    is DrawerRow.App -> {
                        val entry = row.entry
                        // If in shortcut selection mode, assign to shortcut instead of launching
                        if (localShortcut != null) {
                            shortcutListener.onShortcut(entry.first, entry.second, textView, entry.third, localShortcut, shortcutIndex)
                        } else {
                            itemClickListener.onItemClick(entry.first, entry.second)
                        }
                    }
                    else -> Unit
                }
            }

            // Long press opens action menu or assigns shortcut
            textView.setOnLongClickListener {
                val localShortcut = shortcutTextView
                when (val row = rows.getOrNull(bindingAdapterPosition)) {
                    is DrawerRow.Folder -> {
                        if (localShortcut != null) {
                            folderListener.onFolderShortcut(row.folder, localShortcut, shortcutIndex)
                        } else {
                            folderListener.onFolderLongClick(row.folder)
                        }
                    }
                    is DrawerRow.App -> {
                        val entry = row.entry
                        // In shortcut mode, long press also assigns
                        if (localShortcut != null) {
                            shortcutListener.onShortcut(entry.first, entry.second, textView, entry.third, localShortcut, shortcutIndex)
                        } else {
                            // Normal mode: show action menu
                            itemLongClickListener.onItemLongClick(entry.first, entry.second, entry.third)
                        }
                    }
                    else -> Unit
                }
                true
            }

            // Accessibility actions are stable across the holder lifetime; add them once.
            ViewCompat.addAccessibilityAction(textView, activity.getString(R.string.close_app_menu)) { _, _ ->
                activity.backToHome()
                true
            }
            ViewCompat.addAccessibilityAction(textView, activity.getString(R.string.switch_to_contacts)) { _, _ ->
                activity.switchMenus()
                true
            }
        }
    }

    // ============================================
    // Adapter Methods
    // ============================================

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == VIEW_TYPE_HEADER) {
            return HeaderViewHolder(inflater.inflate(R.layout.app_section_header, parent, false) as TextView)
        }
        return AppViewHolder(inflater.inflate(R.layout.app_item_layout, parent, false))
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is DrawerRow.Header -> VIEW_TYPE_HEADER
        is DrawerRow.App -> VIEW_TYPE_APP
        is DrawerRow.Folder -> VIEW_TYPE_FOLDER
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows.getOrNull(position)) {
            is DrawerRow.Header -> if (holder is HeaderViewHolder) bindHeader(holder.textView, row.title)
            is DrawerRow.Folder -> if (holder is AppViewHolder) bindFolder(holder, row.folder)
            is DrawerRow.App -> if (holder is AppViewHolder) bindApp(holder, row)
            null -> Unit
        }
    }

    private fun bindHeader(header: TextView, @StringRes title: Int) {
        header.setText(title)
        header.typeface = cachedTypeface
        applyTextColor(header)
    }

    private fun bindFolder(holder: AppViewHolder, folder: AppFolder) {
        holder.editView.visibility = View.INVISIBLE
        holder.textView.setCompoundDrawablesWithIntrinsicBounds(drawableFolder, null, drawableEmpty, null)
        styleRow(holder)
        holder.textView.text = folder.name
        holder.textView.visibility = View.VISIBLE
    }

    private fun styleRow(holder: AppViewHolder) {
        uiUtils.setAppAlignment(holder.textView, cachedAlignment, holder.editText)
        uiUtils.setAppSize(holder.textView, cachedSize, holder.editText)
        uiUtils.setItemSpacing(holder.textView, cachedSpacing)
        uiUtils.setTextFont(holder.listItem, cachedTypeface)
        applyTextColor(holder.textView)
    }

    private fun applyTextColor(textView: TextView) {
        textView.setTextColor(cachedTextColor)
        if (cachedTextShadowEnabled) {
            textView.setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
        } else {
            textView.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
        }
    }

    private fun bindApp(holder: AppViewHolder, row: DrawerRow.App) {
        holder.editView.visibility = View.INVISIBLE
        val app = row.entry
        val appComponent = app.first.componentName.flattenToString()

        // mutate() each drawable to avoid cross-item colorFilter leakage since
        // ResourcesCompat.getDrawable returns shared instances.
        val tint = BlendModeColorFilter(cachedTextColor, BlendMode.SRC_ATOP)
        val leftDrawable = when {
            row.pinned && app.third != 0 -> drawablePinFilled
            row.pinned -> drawablePin
            app.third != 0 -> drawableWork
            else -> drawableEmpty
        }?.mutate()?.apply { colorFilter = tint }

        holder.textView.setCompoundDrawablesWithIntrinsicBounds(leftDrawable, null, drawableEmpty, null)

        styleRow(holder)

        // Set app name or removal placeholder
        if (appUtils.isAppInstalled(app.first.applicationInfo.packageName, app.third)) {
            val displayName = sharedPreferenceManager.getAppName(
                appComponent,
                app.third,
                AppNameResolver.resolveBaseLabel(activity, app.first)
            )
            holder.textView.text = displayName
            holder.editText.setText(displayName)
        } else {
            holder.textView.text = activity.getString(R.string.removing)
        }

        holder.textView.visibility = View.VISIBLE
    }

    override fun getItemCount(): Int {
        return rows.size
    }

    /** Maps an index in the current app list to its adapter position, or -1. */
    fun adapterPositionOfApp(appIndex: Int): Int = appRowPositions.getOrElse(appIndex) { -1 }

    /** Pinned state is read once per rebuild here instead of once per bind. */
    private fun buildRows(): List<DrawerRow> {
        val pinned = sharedPreferenceManager.getPinnedApps()
        val appRows = apps.map { DrawerRow.App(it, (it.first.componentName.flattenToString() to it.third) in pinned) }
        if (!isSectioned) return appRows

        // Apps arrive sorted with pinned ones first.
        val pinnedCount = appRows.indexOfFirst { !it.pinned }.let { if (it < 0) appRows.size else it }
        val rows = ArrayList<DrawerRow>(appRows.size + folders.size + 3)
        if (pinnedCount > 0) {
            rows += DrawerRow.Header(R.string.pinned_section)
            rows += appRows.subList(0, pinnedCount)
        }
        if (folders.isNotEmpty()) {
            rows += DrawerRow.Header(R.string.folders)
            folders.mapTo(rows) { DrawerRow.Folder(it) }
        }
        if (pinnedCount < appRows.size) {
            if (rows.isNotEmpty()) rows += DrawerRow.Header(R.string.all_apps)
            rows += appRows.subList(pinnedCount, appRows.size)
        }
        return rows
    }

    private fun setRows(newRows: List<DrawerRow>) {
        rows = newRows
        appRowPositions = newRows.indices.filter { newRows[it] is DrawerRow.App }.toIntArray()
    }

    private fun diffToRows(newRows: List<DrawerRow>) {
        val diffResult = DiffUtil.calculateDiff(DrawerRowDiffCallback(rows, newRows))
        setRows(newRows)
        diffResult.dispatchUpdatesTo(this)
    }

    /** Folders are only listed in the sectioned (non-search) view. */
    fun setFolders(newFolders: List<AppFolder>) {
        if (newFolders == folders) return
        folders = newFolders
        if (isSectioned) diffToRows(buildRows())
    }

    /**
     * Updates app list with DiffUtil for efficient animations.
     * Preserves RecyclerView position when possible.
     *
     * @param newApps New list of apps
     */
    fun updateApps(newApps: List<Triple<LauncherActivityInfo, UserHandle, Int>>) {
        apps = newApps
        isSectioned = true
        diffToRows(buildRows())
    }

    /**
     * Replaces app list without diff calculation.
     * Use for search results where positions change significantly.
     *
     * @param newApps New list of apps
     */
    @SuppressLint("NotifyDataSetChanged")
    fun setApps(newApps: List<Triple<LauncherActivityInfo, UserHandle, Int>>) {
        apps = newApps
        isSectioned = false
        setRows(buildRows())
        notifyDataSetChanged()
    }

    private companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_HEADER = 1
        const val VIEW_TYPE_FOLDER = 2
    }
}
