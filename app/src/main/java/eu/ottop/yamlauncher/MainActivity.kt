package eu.ottop.yamlauncher

/**
 * MainActivity serves as the primary Android launcher activity for YAM Launcher.
 * It handles the home screen display, app menu, gesture detection, search functionality,
 * contacts integration, weather display, and various launcher interactions.
 *
 * Key responsibilities:
 * - Display home screen with clock, date, weather info, and app shortcuts
 * - Manage app menu with search and filtering capabilities
 * - Handle swipe gestures for navigation and app launching
 * - Integrate with contacts for quick access
 * - Display battery status and notification indicators
 * - Support multi-user/work profile environments
 *
 * The activity implements multiple listener interfaces to handle app menu interactions,
 * shortcut selection, contact actions, and bottom sheet dialogs.
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewSwitcher
import androidx.appcompat.widget.AppCompatButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.database.getStringOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.marginLeft
import androidx.core.view.updateLayoutParams
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import eu.ottop.yamlauncher.databinding.ActivityMainBinding
import eu.ottop.yamlauncher.settings.AppFolder
import eu.ottop.yamlauncher.settings.SettingsActivity
import eu.ottop.yamlauncher.settings.ShortcutSetting
import eu.ottop.yamlauncher.settings.SharedPreferenceManager
import eu.ottop.yamlauncher.tasks.BatteryReceiver
import eu.ottop.yamlauncher.tasks.NotificationEventBus
import eu.ottop.yamlauncher.tasks.NotificationListener
import eu.ottop.yamlauncher.tasks.NowPlayingManager
import eu.ottop.yamlauncher.tasks.ScreenLockService
import eu.ottop.yamlauncher.utils.Animations
import eu.ottop.yamlauncher.utils.AppMenuEdgeFactory
import eu.ottop.yamlauncher.utils.AppMenuLinearLayoutManager
import eu.ottop.yamlauncher.utils.AppNameResolver
import eu.ottop.yamlauncher.utils.AppUtils
import eu.ottop.yamlauncher.utils.BiometricUtils
import eu.ottop.yamlauncher.utils.CurboxApiClient
import eu.ottop.yamlauncher.utils.GestureUtils
import eu.ottop.yamlauncher.utils.Logger
import eu.ottop.yamlauncher.utils.PermissionUtils
import eu.ottop.yamlauncher.utils.StringUtils
import eu.ottop.yamlauncher.utils.UIUtils
import eu.ottop.yamlauncher.utils.WeatherCache
import eu.ottop.yamlauncher.utils.WeatherSchedule
import eu.ottop.yamlauncher.utils.WeatherSystem
import eu.ottop.yamlauncher.utils.isPaused
import eu.ottop.yamlauncher.views.AlphabetIndexView
import eu.ottop.yamlauncher.views.NowPlayingView
import eu.ottop.yamlauncher.views.WidgetStackView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import java.lang.reflect.Method
import kotlin.math.abs


class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener, AppMenuAdapter.OnItemClickListener,
    AppMenuAdapter.OnShortcutListener, AppMenuAdapter.OnItemLongClickListener, ContactsAdapter.OnContactClickListener,
    ContactsAdapter.OnContactShortcutListener, AppActionBottomSheet.AppActionListener, AppMenuAdapter.OnFolderListener,
    AppActionBottomSheet.FolderActionListener {

    /**
     * Resource IDs for the 15 home screen shortcut slots.
     * These correspond to TextViews in the layout that can be configured to launch apps or contacts.
     */
    companion object {
        val SHORTCUT_IDS = listOf(
            R.id.app1, R.id.app2, R.id.app3, R.id.app4, R.id.app5,
            R.id.app6, R.id.app7, R.id.app8, R.id.app9, R.id.app10,
            R.id.app11, R.id.app12, R.id.app13, R.id.app14, R.id.app15
        )

        /** Newer types are plain ints, so they simply never match on older devices. */
        @SuppressLint("InlinedApi")
        private val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        private const val PACKAGE_REFRESH_DEBOUNCE_MS = 300L
    }

    /** Handles weather data fetching and temperature display */
    private lateinit var weatherSystem: WeatherSystem
    /** Last successful weather reading, persisted across restarts */
    private lateinit var weatherCache: WeatherCache
    /** Wakes the weather loop when weather settings change */
    private val weatherSettingsChanged = Channel<Unit>(Channel.CONFLATED)
    /** Consecutive failed weather fetches, used for retry backoff */
    private var weatherFailures = 0
    private var lastWeatherFailureMs: Long? = null
    /** Reads today's screen time from Curbox's local API. */
    private lateinit var curboxApiClient: CurboxApiClient
    /** Utilities for launching apps and managing installed applications */
    private lateinit var appUtils: AppUtils
    /** Manages biometric authentication for locked settings */
    private lateinit var biometricUtils: BiometricUtils

    private val stringUtils = StringUtils()
    private val permissionUtils = PermissionUtils()
    private lateinit var uiUtils: UIUtils
    private lateinit var gestureUtils: GestureUtils

    private val appMenuLinearLayoutManager = AppMenuLinearLayoutManager(this@MainActivity)
    private val contactMenuLinearLayoutManager = AppMenuLinearLayoutManager(this@MainActivity)
    private val appMenuEdgeFactory = AppMenuEdgeFactory(this@MainActivity)

    private lateinit var sharedPreferenceManager: SharedPreferenceManager

    private lateinit var animations: Animations
    private lateinit var logger: Logger

    private lateinit var clock: TextClock
    private var clockMargin = 0
    private lateinit var dateText: TextClock
    private var dateElements = mutableListOf<String>()

    private lateinit var menuView: ViewSwitcher
    private lateinit var menuTitle: TextInputEditText
    private lateinit var appRecycler: RecyclerView
    private lateinit var contactRecycler: RecyclerView
    private lateinit var alphabetIndex: AlphabetIndexView
    private lateinit var searchSwitcher: ImageView
    private lateinit var internetSearch: ImageView
    private lateinit var searchView: TextInputEditText
    private var appAdapter: AppMenuAdapter? = null
    private var contactAdapter: ContactsAdapter? = null
    private var batteryReceiver: BatteryReceiver? = null

    private lateinit var binding: ActivityMainBinding
    private lateinit var launcherApps: LauncherApps
    private lateinit var installedApps: List<Triple<LauncherActivityInfo, UserHandle, Int>>
    private var currentFilteredApps: List<Triple<LauncherActivityInfo, UserHandle, Int>> = listOf()
    /** Decoded once and kept sorted in memory; preferences are only touched when folders change. */
    private var folders: List<AppFolder> = emptyList()

    private lateinit var preferences: SharedPreferences

    private var isBatteryReceiverRegistered = false
    private var notificationCollectionJob: Job? = null
    /** Created on first use; only registered with the system while the activity is started. */
    private var nowPlayingManager: NowPlayingManager? = null
    /** Inflated from the ViewStub the first time something plays. */
    private var nowPlayingView: NowPlayingView? = null
    /** Created on first use; only listening for widget updates while the activity is started. */
    private var appWidgetHost: AppWidgetHost? = null
    private var isWidgetHostListening = false
    /** Inflated from the ViewStub the first time the widget stack is enabled. */
    private var widgetStackView: WidgetStackView? = null
    private var isSearchActive = false
    private var isInitialOpen = false
    private var canLaunchShortcut = true
    private var showHidden = false
    private var searchJob: Job? = null
    private var isResettingSearch = false
    private var isDrawerOpen = false

    private data class AppSearchEntry(
        val item: Triple<LauncherActivityInfo, UserHandle, Int>,
        val cleaned: String,
        val cleanedLower: String
    )

    private var appSearchIndex: List<AppSearchEntry> = emptyList()
    private var appSearchIndexDirty = true

    /**
     * Builds a searchable index of installed apps with cleaned, lowercase names.
     * This pre-computed index enables fast fuzzy search without repeated string processing.
     *
     * @param apps List of installed app information with user handles and profile indices
     * @return List of search entries with original app data and cleaned name variants
     */
    private fun buildAppSearchIndex(apps: List<Triple<LauncherActivityInfo, UserHandle, Int>>): List<AppSearchEntry> {
        return apps.mapNotNull { appItem ->
            try {
                val name = sharedPreferenceManager.getAppName(
                    appItem.first.componentName.flattenToString(),
                    appItem.third,
                    AppNameResolver.resolveBaseLabel(this, appItem.first)
                ).toString()

                val cleaned = stringUtils.cleanString(name).orEmpty()
                if (cleaned.isNotEmpty()) {
                    AppSearchEntry(appItem, cleaned, cleaned.lowercase())
                } else {
                    null
                }
            } catch (e: Exception) {
                logger.w("MainActivity", "Error building search index for app: ${appItem.first.componentName.packageName}")
                null
            }
        }
    }

    private var swipeThreshold = 100
    private var swipeVelocityThreshold = 100

    private lateinit var clockApp: Pair<LauncherActivityInfo?, Int?>
    private lateinit var dateApp: Pair<LauncherActivityInfo?, Int?>
    private lateinit var weatherApp: Pair<LauncherActivityInfo?, Int?>

    private lateinit var leftSwipeActivity: Pair<LauncherActivityInfo?, Int?>
    private lateinit var rightSwipeActivity: Pair<LauncherActivityInfo?, Int?>
    private lateinit var doubleTapApp: Pair<LauncherActivityInfo?, Int?>

    private lateinit var gestureDetector: GestureDetector
    private lateinit var shortcutGestureDetector: GestureDetector
    private lateinit var dateGestureDetector: GestureDetector

    var returnAllowed = true

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Keeps the drawer and shortcuts in sync with installs, updates and removals, and re-reads
     * pause state the moment an app timer runs out or resets, even while on the home screen.
     */
    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = onPackagesChanged(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = onPackagesChanged(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = onPackagesChanged(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            onPackagesChanged(*packageNames)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            onPackagesChanged(*packageNames)
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = onSuspensionChanged()
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = onSuspensionChanged()
    }

    private fun onSuspensionChanged() {
        refreshShortcutLabels()
        updateMusicShortcut()
        lifecycleScope.launch { refreshAppMenu() }
    }

    /** Packages added, updated or removed since the drawer last re-read them. Guarded by itself. */
    private val pendingPackageChanges = HashSet<String>()
    /** Debounces package events: one install or update arrives as a burst of removed, added and changed. */
    private var packageRefreshJob: Job? = null
    /** Set when [refreshAppMenu] was skipped during a search, so it runs once the search ends. */
    private var isAppMenuStale = false

    /** While stopped the change is only recorded; [onResume] refreshes anyway. */
    private fun onPackagesChanged(vararg packageNames: String) {
        synchronized(pendingPackageChanges) { pendingPackageChanges.addAll(packageNames) }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        packageRefreshJob?.cancel()
        packageRefreshJob = lifecycleScope.launch {
            delay(PACKAGE_REFRESH_DEBOUNCE_MS)
            refreshAppMenu()
        }
    }

    private fun takePendingPackageChanges(): Set<String> = synchronized(pendingPackageChanges) {
        pendingPackageChanges.toSet().also { pendingPackageChanges.clear() }
    }

    private var headphonesConnected = false

    /** Registration immediately reports the current devices, so each onStart re-syncs the music row. */
    private val headphoneCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = updateHeadphones()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = updateHeadphones()
    }

    private fun updateHeadphones() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        headphonesConnected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }
        updateMusicShortcut()
    }

    /** Extra row above the shortcuts; never takes a shortcut slot. */
    private fun updateMusicShortcut() {
        val view = binding.musicShortcut
        val (app, profile) = if (headphonesConnected && sharedPreferenceManager.isGestureEnabled("music")) {
            gestureUtils.getSwipeInfo(launcherApps, "music")
        } else {
            null to null
        }
        if (app == null || profile == null) {
            view.visibility = View.GONE
            return
        }
        val user = launcherApps.profiles[profile]
        view.text = sharedPreferenceManager.getAppName(
            app.componentName.flattenToString(),
            profile,
            AppNameResolver.resolveBaseLabel(this, app)
        )
        uiUtils.setPausedStyle(view, app.isPaused())
        view.setOnClickListener {
            if (canLaunchShortcut) appUtils.launchApp(app.componentName, user)
        }
        view.visibility = View.VISIBLE
    }

    /** Lets home-screen widgets mirror touches into the home gesture detector (swipe up, long press, double tap). */
    private val homeGestureForwarder: (MotionEvent) -> Unit = { event ->
        if (!isDrawerOpen && !animations.isInAnim) gestureDetector.onTouchEvent(event)
    }

    /**
     * Called when the activity is first created.
     * Initializes view binding, utilities, preferences, listeners, and starts background tasks.
     * Sets up periodic weather updates via coroutines.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        biometricUtils = BiometricUtils(this)
        setSupportActionBar(null)

        // Apply the background before inflation so the first frame matches the user's preferences.
        UIUtils(this).setBackground(window, applyHomescreenDarkening = true)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setMainVariables()

        uiUtils.adjustInsets(binding.root)

        setShortcuts()
        launcherApps.registerCallback(packageCallback, handler)

        setPreferences()

        setHomeListeners()

        updateWidgetStack()

        // Task to update the weather periodically based on configured interval
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runWeatherUpdates()
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateScreenTime()
                    delay(60_000)
                }
            }
        }

        setupApps()

        // Check if default launcher banner should be shown
        updateDefaultLauncherBanner()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            uiUtils.setLayoutListener(binding.root) // Manually resize app menu on search since adjustResize no longer works with sdk 35
        }
    }

    /**
     * Initializes all utility classes, view references, and gesture detectors.
     * Called during onCreate to set up the core components of the launcher.
     */
    private fun setMainVariables() {
        launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps

        logger = Logger.getInstance(this@MainActivity)
        logger.i("MainActivity", "MainActivity started")

        weatherSystem = WeatherSystem(this@MainActivity)
        weatherCache = WeatherCache(this@MainActivity)
        curboxApiClient = CurboxApiClient(this@MainActivity) {
            lifecycleScope.launch { updateScreenTime() }
        }
        appUtils = AppUtils(this@MainActivity, launcherApps)
        uiUtils = UIUtils(this@MainActivity)
        gestureUtils = GestureUtils(this@MainActivity)
        sharedPreferenceManager = SharedPreferenceManager(this@MainActivity)
        showFolders(sharedPreferenceManager.getFolders())
        animations = Animations(this@MainActivity)

        gestureDetector = GestureDetector(this, GestureListener())
        shortcutGestureDetector = GestureDetector(this, TextGestureListener())
        dateGestureDetector = GestureDetector(this, DateGestureListener())

        clock = binding.textClock

        clockMargin = clock.marginLeft

        dateText = binding.textDate

        dateElements = mutableListOf(dateText.format12Hour.toString(), dateText.format24Hour.toString(), "", "", "")

        menuTitle = binding.menuTitle

        searchView = binding.searchView

        menuView = binding.menuView

        alphabetIndex = binding.alphabetIndex

        searchSwitcher = binding.searchSwitcher
        internetSearch = binding.internetSearch

        preferences = PreferenceManager.getDefaultSharedPreferences(this)
    }

    /**
     * Configures the home screen shortcuts based on user preferences.
     * Shows/hides shortcuts based on configured count and sets up click/long-click listeners.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setShortcuts() {
        val shortcutNo = sharedPreferenceManager.getShortcutNumber()

        binding.musicShortcut.setOnTouchListener { _, event ->
            shortcutGestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        SHORTCUT_IDS.forEachIndexed { index, shortcutId ->
            val textView = findViewById<TextView>(shortcutId)

            if (index >= shortcutNo) {
                textView.visibility = View.GONE
            } else {
                textView.visibility = View.VISIBLE

                val savedView = sharedPreferenceManager.getShortcutSetting(index)

                textView.setCompoundDrawablesWithIntrinsicBounds(ResourcesCompat.getDrawable(resources, R.drawable.ic_empty, null), null, null, null)

                shortcutListeners(index, textView, savedView)

                if (savedView != null) {
                    setShortcutSetup(textView, savedView)
                } else {
                    unsetShortcutSetup(textView)
                }
            }
        }
        uiUtils.setShortcutsAlignment(binding.homeView)
        uiUtils.setShortcutsVAlignment(binding.topSpace, binding.bottomSpace)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun shortcutListeners(index: Int, textView: TextView, savedView: ShortcutSetting?) {
        // Don't go to settings on long click, but keep other gestures functional
        textView.setOnTouchListener { _, event ->
            shortcutGestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        ViewCompat.addAccessibilityAction(textView, getString(R.string.accessibility_set_shortcut)) { _, _ ->
            launchShortcutSelection(index, textView, savedView)
        }

        ViewCompat.addAccessibilityAction(textView, getString(R.string.settings_title)) { _, _ ->
            trySettings()
            true
        }

        ViewCompat.addAccessibilityAction(textView, getString(R.string.open_app_menu)) { _, _ ->
            openAppMenu()
            true
        }

        textView.setOnLongClickListener {
            launchShortcutSelection(index, textView, savedView)
        }
    }

    private fun launchShortcutSelection(index: Int, textView: TextView, savedView: ShortcutSetting?): Boolean {

        if (!sharedPreferenceManager.areShortcutsLocked()) {
            uiUtils.setMenuTitleAlignment(menuTitle)
            uiUtils.setMenuTitleSize(menuTitle)
            menuTitle.hint = textView.text
            menuTitle.setText(textView.text)
            menuTitle.visibility = View.VISIBLE
            if (savedView != null) {
                setRenameShortcutListener(index, textView)
            }
            appAdapter?.shortcutIndex = index
            appAdapter?.shortcutTextView = textView
            contactAdapter?.shortcutIndex = index
            contactAdapter?.shortcutTextView = textView
            internetSearch.visibility = View.GONE

            if (sharedPreferenceManager.showHiddenShortcuts()) {
                lifecycleScope.launch(Dispatchers.Default) {
                    showHidden = true
                    refreshAppMenu()
                    runOnUiThread {
                        toAppMenu() // This is intentionally slow to happen
                    }
                }
                return true
            }
            toAppMenu()

            return true
        }

        return false
    }

    private fun setRenameShortcutListener(index: Int, textView: TextView) {
        menuTitle.setOnEditorActionListener { _, actionId, _ ->

            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (menuTitle.text.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, getString(R.string.empty_rename), Toast.LENGTH_SHORT).show()
                    return@setOnEditorActionListener true
                }
                val imm =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(menuTitle.windowToken, 0)
                val savedView = sharedPreferenceManager.getShortcutSetting(index)
                if (savedView == null) {
                    logger.w("MainActivity", "Cannot rename an invalid shortcut at index $index")
                    unsetShortcutSetup(textView)
                    return@setOnEditorActionListener true
                }
                val newName = menuTitle.text.toString()
                val folderId = AppFolder.idFromComponentKey(savedView.componentName)
                val activity = if (savedView.isContact || folderId != null) null else shortcutActivity(savedView)
                when {
                    folderId != null -> AppFolder.sanitizeName(newName).takeIf { it.isNotEmpty() }?.let { name ->
                        saveFolders(folders.map { if (it.id == folderId) it.copy(name = name) else it })
                    }
                    activity != null -> {
                        sharedPreferenceManager.setAppName(activity.componentName.flattenToString(), savedView.profile, newName)
                        appSearchIndexDirty = true
                        // Drawer rows skip rebinding on name-only changes, so force it.
                        appAdapter?.notifyDataSetChanged()
                    }
                }
                sharedPreferenceManager.setShortcut(index, newName, savedView.componentName, savedView.profile, savedView.isContact)
                refreshShortcutLabels()
                backToHome()
                return@setOnEditorActionListener true
            }
            false
        }
    }

    private fun toAppMenu(showKeyboard: Boolean = false) {
        uiUtils.setContactsVisibility(searchSwitcher, binding.searchLayout, binding.searchReplacement)

        try {
            // The menu opens from the top
            if (::appRecycler.isInitialized) {
                appRecycler.scrollToPosition(0)
            }
            if (::menuView.isInitialized) {
                menuView.displayedChild = 0
            }
            if (::searchSwitcher.isInitialized && searchSwitcher.isVisible && ::contactRecycler.isInitialized) {
                contactRecycler.scrollToPosition(0)
                setAppViewDetails()
            }
        } catch (e: Exception) {
            logger.w("MainActivity", "Error in toAppMenu: ${e.message}")
        }
        isDrawerOpen = true
        animations.showApps(binding.homeView, binding.appView)
        animations.backgroundIn(this@MainActivity)

        val shouldShowKeyboard = showKeyboard || sharedPreferenceManager.isAutoKeyboardEnabled()
        if (shouldShowKeyboard) {
            if (!showKeyboard) isInitialOpen = true
            searchView.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun unsetShortcutSetup(textView: TextView) {
        textView.text = getString(R.string.shortcut_default)
        uiUtils.setPausedStyle(textView, false)
        unsetShortcutListeners(textView)
    }

    private fun unsetShortcutListeners(textView: TextView) {
        textView.setOnClickListener {
            if (canLaunchShortcut) {
                Toast.makeText(this, getString(R.string.shortcut_default_click), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Apps and folders show their drawer name so both places always agree. The label stored with
     * the shortcut is only used for contacts and for targets that no longer exist.
     */
    private fun shortcutLabel(setting: ShortcutSetting, activity: LauncherActivityInfo?): String {
        if (setting.isContact) return setting.label
        AppFolder.idFromComponentKey(setting.componentName)?.let { id ->
            return folders.firstOrNull { it.id == id }?.name ?: setting.label
        }
        if (activity == null) return setting.label
        return sharedPreferenceManager.getAppName(
            activity.componentName.flattenToString(),
            setting.profile,
            AppNameResolver.resolveBaseLabel(this, activity)
        )?.toString() ?: setting.label
    }

    /** Accepts both full component names and the bare package names older versions stored. */
    private fun shortcutActivity(setting: ShortcutSetting): LauncherActivityInfo? {
        val user = launcherApps.profiles.getOrNull(setting.profile) ?: return null
        val component = ComponentName.unflattenFromString(setting.componentName)
        val activities = try {
            launcherApps.getActivityList(component?.packageName ?: setting.componentName, user)
        } catch (_: Exception) {
            return null
        }
        return if (component == null) activities.firstOrNull() else activities.firstOrNull { it.componentName == component }
    }

    /** Sets the label and paused style from one activity lookup. */
    private fun bindShortcutLabel(textView: TextView, setting: ShortcutSetting) {
        val isApp = !setting.isContact && AppFolder.idFromComponentKey(setting.componentName) == null
        val activity = if (isApp) shortcutActivity(setting) else null
        textView.text = shortcutLabel(setting, activity).ifBlank { getString(R.string.shortcut_default) }
        uiUtils.setPausedStyle(textView, activity?.isPaused() == true)
    }

    private fun refreshShortcutLabels() {
        SHORTCUT_IDS.forEachIndexed { index, id ->
            val setting = sharedPreferenceManager.getShortcutSetting(index) ?: return@forEachIndexed
            bindShortcutLabel(findViewById(id), setting)
        }
    }

    private fun setShortcutSetup(textView: TextView, savedView: ShortcutSetting) {
        bindShortcutLabel(textView, savedView)

        if (savedView.isContact) {
            setShortcutContactListeners(textView, savedView.profile)
            return
        }

        val folderId = AppFolder.idFromComponentKey(savedView.componentName)
        if (folderId != null) {
            val icon = ResourcesCompat.getDrawable(resources, R.drawable.folder_15px, null)
                ?.mutate()?.apply { setTint(sharedPreferenceManager.getTextColor()) }
            textView.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            textView.setOnClickListener {
                if (canLaunchShortcut) openFolder(folderId)
            }
            return
        }

        if (savedView.profile != 0) {
            textView.setCompoundDrawablesWithIntrinsicBounds(ResourcesCompat.getDrawable(resources, R.drawable.ic_work_app, null), null, null, null)
        }

        setShortcutListeners(textView, savedView)
    }

    private fun setShortcutListeners(textView: TextView, savedView: ShortcutSetting) {
        textView.setOnClickListener {
            if (canLaunchShortcut) {
                val profileIndex = validateProfileIndex(savedView) ?: return@setOnClickListener
                val componentName = resolveComponentName(savedView, profileIndex) ?: return@setOnClickListener
                // Double-check profile is valid before access
                if (profileIndex in launcherApps.profiles.indices) {
                    appUtils.launchApp(componentName, launcherApps.profiles[profileIndex])
                } else {
                    logger.w("MainActivity", "Invalid profile index: $profileIndex")
                    Toast.makeText(this@MainActivity, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun validateProfileIndex(savedView: ShortcutSetting): Int? {
        val profileIndex = savedView.profile
        if (profileIndex !in launcherApps.profiles.indices) {
            logger.w("MainActivity", "Failed to launch shortcut: invalid profile index")
            Toast.makeText(this, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
            return null
        }
        return profileIndex
    }

    private fun resolveComponentName(savedView: ShortcutSetting, profileIndex: Int): ComponentName? {
        val userHandle = launcherApps.profiles[profileIndex]
        val componentString = savedView.componentName

        if (componentString.isEmpty()) {
            logger.w("MainActivity", "Failed to launch shortcut: empty component")
            Toast.makeText(this, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
            return null
        }

        return if (componentString.contains("/")) {
            parseExplicitComponent(componentString, userHandle)
        } else {
            resolveMainActivity(componentString, userHandle)
        }
    }

    private fun parseExplicitComponent(componentString: String, userHandle: UserHandle): ComponentName? {
        val parts = componentString.split("/", limit = 2)
        if (parts.size != 2) {
            logger.w("MainActivity", "Failed to launch shortcut: invalid component $componentString")
            Toast.makeText(this, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
            return null
        }

        val cn = ComponentName(parts[0], parts[1])
        if (launcherApps.getActivityList(parts[0], userHandle).none { it.componentName == cn }) {
            logger.w("MainActivity", "Failed to launch shortcut: $componentString not found")
            Toast.makeText(this, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
            return null
        }
        return cn
    }

    private fun resolveMainActivity(componentString: String, userHandle: UserHandle): ComponentName? {
        val mainActivity = launcherApps.getActivityList(componentString, userHandle).firstOrNull()
        if (mainActivity != null) {
            return mainActivity.componentName
        }
        logger.w("MainActivity", "Failed to launch shortcut: $componentString not found")
        Toast.makeText(this, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
        return null
    }

    private fun setShortcutContactListeners(textView: TextView, contactId: Int) {
        textView.setOnClickListener {
            onContactClick(contactId)
        }
    }

    private fun setPreferences() {
        // Window background is set in onCreate before
        // setContentView so the first frame already reflects the user's prefs.
        val typeface = uiUtils.resolveTypeface()
        uiUtils.setTextFont(binding.homeView, typeface)
        uiUtils.setFont(searchView, typeface)
        uiUtils.setFont(menuTitle, typeface)

        uiUtils.setTextColors(binding.homeView)
        uiUtils.setStatusBarColor(window)

        uiUtils.setClockVisibility(clock)
        uiUtils.setDateVisibility(dateText)
        uiUtils.setSearchVisibility(searchView, binding.searchLayout, binding.searchReplacement)

        uiUtils.setClockAlignment(clock, dateText)
        uiUtils.setSearchAlignment(searchView)

        uiUtils.setClockSize(clock)
        uiUtils.setDateSize(dateText)
        uiUtils.setShortcutsSize(binding.homeView)
        uiUtils.setSearchSize(searchView)

        uiUtils.setShortcutsSpacing(binding.homeView)

        uiUtils.setStatusBar(window)
        uiUtils.setMenuItemColors(searchView)
        uiUtils.setMenuItemColors(menuTitle, "A9")

        clockApp = gestureUtils.getSwipeInfo(launcherApps, "clock")
        dateApp = gestureUtils.getSwipeInfo(launcherApps, "date")

        leftSwipeActivity = gestureUtils.getSwipeInfo(launcherApps, "left")
        rightSwipeActivity = gestureUtils.getSwipeInfo(launcherApps, "right")
        doubleTapApp = gestureUtils.getSwipeInfo(launcherApps, "doubleTap")
        weatherApp = gestureUtils.getSwipeInfo(launcherApps, "weather")

        swipeThreshold = sharedPreferenceManager.getSwipeThreshold()
        swipeVelocityThreshold = sharedPreferenceManager.getSwipeVelocity()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setHomeListeners() {
        registerBatteryReceiver()
        registerNotificationReceiver()

        if (!sharedPreferenceManager.isBatteryEnabled()) {
            unregisterBatteryReceiver()
        }

        preferences.registerOnSharedPreferenceChangeListener(this)

        binding.homeView.setOnTouchListener { _, event ->
            if (isDrawerOpen || animations.isInAnim) {
                return@setOnTouchListener false
            }
            super.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }

        // Set up default launcher banner button
        findViewById<android.widget.TextView>(R.id.setDefaultLauncherButton)?.setOnClickListener {
            val intent = Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                logger.w("MainActivity", "Unable to launch home settings")
                Toast.makeText(this, getString(R.string.unable_to_launch_settings), Toast.LENGTH_SHORT).show()
            }
        }

        clock.setOnClickListener {
            if (sharedPreferenceManager.isClockGestureEnabled()) {
                handleLaunchOrFallback(clockApp, "clock") {
                    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                    if (intent.resolveActivity(packageManager) != null) startActivity(intent)
                }
            }
        }

        dateText.setOnTouchListener { _, event -> dateGestureDetector.onTouchEvent(event) }

        clock.setOnLongClickListener { _ ->
            trySettings()
            true
        }

        ViewCompat.addAccessibilityAction(binding.homeView, getString(R.string.settings_title)) { _, _ ->
            trySettings()
            true
        }

        ViewCompat.addAccessibilityAction(binding.homeView, getString(R.string.open_app_menu)) { _, _ ->
            openAppMenu()
            true
        }

        // Return to home on back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backToHome()
            }
        })
    }

    /**
     * Attempts to launch the configured gesture app, falling back to a default action if
     * the gesture isn't configured or the launch fails.
     */
    private fun handleLaunchOrFallback(
        target: Pair<LauncherActivityInfo?, Int?>,
        gestureKey: String,
        requireGestureEnabled: Boolean = true,
        fallback: () -> Unit,
    ) {
        val (activity, profileIndex) = target
        if (
            (!requireGestureEnabled || sharedPreferenceManager.isGestureEnabled(gestureKey)) &&
            activity != null &&
            profileIndex != null &&
            profileIndex in launcherApps.profiles.indices
        ) {
            try {
                launcherApps.startMainActivity(activity.componentName, launcherApps.profiles[profileIndex], null, null)
            } catch (e: SecurityException) {
                try {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = activity.componentName
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e2: Exception) {
                    logger.e("MainActivity", "Failed to launch $gestureKey gesture app via Intent fallback", e2)
                    Toast.makeText(this, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                logger.e("MainActivity", "Failed to launch $gestureKey gesture app", e)
                Toast.makeText(this, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
            }
        } else {
            fallback()
        }
    }

    private enum class DateSection { WEATHER, BATTERY }

    /** Finds the logical section under a tap, including sections that wrap to another line. */
    private fun dateSectionAt(event: MotionEvent): DateSection? {
        val layout = dateText.layout ?: return null
        if (layout.lineCount == 0) return null

        val vertical = (event.y - dateText.totalPaddingTop).toInt()
            .coerceIn(0, (layout.height - 1).coerceAtLeast(0))
        val line = layout.getLineForVertical(vertical)
        val offset = layout.getOffsetForHorizontal(
            line,
            event.x - dateText.totalPaddingLeft
        )
        val rendered = dateText.text?.toString().orEmpty()
        var cursor = 0

        // The rendered date/time is followed by each non-empty dynamic section.
        val sections = listOf(
            DateSection.WEATHER to dateElements[2],
            DateSection.BATTERY to dateElements[3]
        ).filter { it.second.isNotEmpty() }
        for ((section, value) in sections) {
            val separatorStart = rendered.indexOf(" | ", cursor)
            if (separatorStart < 0) return null
            val start = separatorStart + 3
            val end = (start + value.length).coerceAtMost(rendered.length)
            if (offset in start until end) return section
            cursor = end
        }
        return null
    }

    private fun openDateApp() {
        if (!sharedPreferenceManager.isDateGestureEnabled()) return
        handleLaunchOrFallback(dateApp, "date") {
            try {
                startActivity(Intent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)))
            } catch (_: ActivityNotFoundException) {
                logger.w("MainActivity", "No calendar app found when clicking date")
                Toast.makeText(this, getString(R.string.no_calendar_app), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openWeatherApp() {
        handleLaunchOrFallback(weatherApp, "weather", requireGestureEnabled = false) {
            val intent = Intent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_WEATHER))
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, getString(R.string.no_weather_app), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            logger.w("MainActivity", "No battery settings activity found")
            Toast.makeText(this, getString(R.string.unable_to_launch_settings), Toast.LENGTH_SHORT).show()
        }
    }

    private fun trySettings() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (sharedPreferenceManager.isSettingsLocked()) {
                biometricUtils.startBiometricSettingsAuth(object : BiometricUtils.CallbackSettings {
                    override fun onAuthenticationSucceeded() {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    }

                    override fun onAuthenticationFailed() {
                        logger.w("MainActivity", "Biometric authentication failed")
                    }

                    override fun onAuthenticationError(errorCode: Int, errorMessage: CharSequence?) {
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED -> logger.i("MainActivity", "Biometric authentication cancelled by user")
                            else -> logger.e("MainActivity", "Biometric authentication error: $errorMessage (code: $errorCode)")
                        }
                    }
                })
            } else {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
    }

    private fun registerBatteryReceiver() {
        if (!isBatteryReceiverRegistered) {
            try {
                batteryReceiver = BatteryReceiver.register(this, this@MainActivity)
                isBatteryReceiverRegistered = true
            } catch (e: Exception) {
                logger.w("MainActivity", "Failed to register battery receiver: ${e.message}")
            }
        }
    }

    private fun unregisterBatteryReceiver() {
        if (isBatteryReceiverRegistered && batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (e: Exception) {
                logger.w("MainActivity", "Failed to unregister battery receiver: ${e.message}")
            } finally {
                batteryReceiver = null
                isBatteryReceiverRegistered = false
            }
        }
    }

    private fun openAppMenu() {
        appAdapter?.shortcutTextView = null
        contactAdapter?.shortcutTextView = null
        menuTitle.visibility = View.GONE
        uiUtils.setWebSearchVisibility(internetSearch)
        toAppMenu()
    }

    /**
     * Handles shared preference changes to dynamically update UI elements.
     * Only reloads specific components affected by the changed preference key
     * rather than reloading everything for better performance.
     *
     * @param preferences The SharedPreferences instance that changed
     * @param key The key of the preference that changed
     */
    @SuppressLint("UseKtx")
    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (preferences == null || key == null) return

        when (key) {
            "bgColor", "homescreenDarkening" -> uiUtils.setBackground(window, applyHomescreenDarkening = true)

            "textColor" -> {
                uiUtils.setTextColors(binding.homeView)
                uiUtils.setStatusBarColor(window)
                uiUtils.setMenuItemColors(searchView)
                uiUtils.setMenuItemColors(menuTitle, "A9")
                uiUtils.setImageColor(searchSwitcher)
                uiUtils.setImageColor(internetSearch)
                if (sharedPreferenceManager.isAlphabetIndexEnabled()) {
                    alphabetIndex.setTextColor(sharedPreferenceManager.getTextColor())
                    alphabetIndex.setTextShadow(sharedPreferenceManager.isTextShadowEnabled())
                }
                appAdapter?.onPreferencesChanged()
                contactAdapter?.onPreferencesChanged()
            }

            "textShadow" -> {
                uiUtils.setTextColors(binding.homeView)
                uiUtils.setMenuItemColors(searchView)
                uiUtils.setMenuItemColors(menuTitle, "A9")
                uiUtils.setImageColor(searchSwitcher)
                uiUtils.setImageColor(internetSearch)
                if (sharedPreferenceManager.isAlphabetIndexEnabled()) {
                    alphabetIndex.setTextShadow(sharedPreferenceManager.isTextShadowEnabled())
                }
                appAdapter?.onPreferencesChanged()
                contactAdapter?.onPreferencesChanged()
            }

            "textFont", "textStyle" -> {
                val typeface = uiUtils.resolveTypeface()
                uiUtils.setTextFont(binding.homeView, typeface)
                uiUtils.setFont(searchView, typeface)
                uiUtils.setFont(menuTitle, typeface)
                appAdapter?.onPreferencesChanged()
                contactAdapter?.onPreferencesChanged()
            }

            "clockEnabled" -> uiUtils.setClockVisibility(clock)
            "dateEnabled" -> uiUtils.setDateVisibility(dateText)
            "screenTimeEnabled" -> lifecycleScope.launch { updateScreenTime() }
            "searchEnabled" -> uiUtils.setSearchVisibility(searchView, binding.searchLayout, binding.searchReplacement)
            "barVisibility" -> uiUtils.setStatusBar(window)

            "clockAlignment" -> uiUtils.setClockAlignment(clock, dateText)
            "shortcutAlignment" -> uiUtils.setShortcutsAlignment(binding.homeView)
            "shortcutVAlignment" -> uiUtils.setShortcutsVAlignment(binding.topSpace, binding.bottomSpace)
            "searchAlignment" -> uiUtils.setSearchAlignment(searchView)

            "clockSize" -> uiUtils.setClockSize(clock)
            "dateSize" -> uiUtils.setDateSize(dateText)
            "shortcutSize" -> uiUtils.setShortcutsSize(binding.homeView)
            "searchSize" -> uiUtils.setSearchSize(searchView)
            "shortcutWeight" -> uiUtils.setShortcutsSpacing(binding.homeView)

            "clockSwipe", "clockSwipeApp" -> clockApp = gestureUtils.getSwipeInfo(launcherApps, "clock")
            "dateSwipe", "dateSwipeApp" -> dateApp = gestureUtils.getSwipeInfo(launcherApps, "date")
            "weatherSwipeApp" -> weatherApp = gestureUtils.getSwipeInfo(launcherApps, "weather")
            "leftSwipe", "leftSwipeApp" -> leftSwipeActivity = gestureUtils.getSwipeInfo(launcherApps, "left")
            "rightSwipe", "rightSwipeApp" -> rightSwipeActivity = gestureUtils.getSwipeInfo(launcherApps, "right")
            "doubleTapAction", "doubleTapSwipeApp" -> doubleTapApp = gestureUtils.getSwipeInfo(launcherApps, "doubleTap")
            "musicSwipe", "musicSwipeApp" -> updateMusicShortcut()

            "batteryEnabled" -> {
                if (sharedPreferenceManager.isBatteryEnabled()) {
                    registerBatteryReceiver()
                } else {
                    unregisterBatteryReceiver()
                    modifyDate("", 3)
                }
            }

            "weatherEnabled", "gpsLocation", "tempUnits", "weatherUpdateInterval" -> rescheduleWeather()
            // In GPS mode the location is written by the weather fetch itself, so it is not a new input.
            "location" -> if (!sharedPreferenceManager.isWeatherGPS()) rescheduleWeather()

            "shortcutNo", "lockShortcuts" -> setShortcuts()
            "swipeThreshold" -> swipeThreshold = sharedPreferenceManager.getSwipeThreshold()
            "swipeVelocity" -> swipeVelocityThreshold = sharedPreferenceManager.getSwipeVelocity()

            "isRestored" -> {
                preferences.edit { remove("isRestored") }
                setPreferences()
                showFolders(sharedPreferenceManager.getFolders())
                setShortcuts()
                updateWidgetStack()
            }

            "contactsEnabled" -> {
                try {
                    contactRecycler
                } catch (_: UninitializedPropertyAccessException) {
                    setupContactRecycler()
                }
                appAdapter?.onPreferencesChanged()
                contactAdapter?.onPreferencesChanged()
            }

            "appMenuAlignment" -> appAdapter?.onPreferencesChanged()
            "appMenuSize" -> appAdapter?.onPreferencesChanged()
            "appSpacing" -> appAdapter?.onPreferencesChanged()

            "alphabetIndexEnabled" -> {
                setAlphabetIndexPosition()
                if (sharedPreferenceManager.isAlphabetIndexEnabled()) {
                    setupAlphabetIndex()
                } else {
                    alphabetIndex.visibility = View.GONE
                }
            }

            "alphabetIndexPosition" -> setAlphabetIndexPosition()

            "notificationDots" -> {
                if (sharedPreferenceManager.isNotificationDotsEnabled()) {
                    if (!NotificationListener.isEnabled(this@MainActivity)) {
                        NotificationListener.requestPermission(this@MainActivity)
                    } else {
                        updateNotificationDots()
                    }
                } else {
                    setShortcuts()
                }
            }

            "nowPlayingEnabled" -> {
                if (sharedPreferenceManager.isNowPlayingEnabled()) {
                    // Only attach while visible; onStart handles the background case.
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startNowPlaying()
                } else {
                    nowPlayingManager?.stop()
                }
            }

            "widgetStackEnabled", "widgetStackIds", "widgetStackHeight" -> updateWidgetStack()
            "widgetStackPosition" -> widgetStackView?.let { placeWidgetStack(it) }
        }
    }

    // ============================================
    // Widget stack
    // ============================================

    private fun updateWidgetStack() {
        if (!sharedPreferenceManager.isWidgetStackEnabled()) {
            stopWidgetHost()
            widgetStackView?.let {
                it.setWidgets(null, emptyList())
                it.visibility = View.GONE
            }
            return
        }
        val host = appWidgetHost ?: AppWidgetHost(this, WidgetStackView.HOST_ID).also { appWidgetHost = it }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startWidgetHost()

        val view = widgetStackView ?: inflateWidgetStack()
        view.updateLayoutParams {
            height = (sharedPreferenceManager.getWidgetStackHeightDp() * resources.displayMetrics.density).toInt()
        }
        view.setWidgets(host, sharedPreferenceManager.getWidgetStackIds())
        view.visibility = if (view.pageCount == 0) View.GONE else View.VISIBLE
    }

    private fun inflateWidgetStack(): WidgetStackView {
        val view = binding.widgetStackStub.inflate() as WidgetStackView
        widgetStackView = view
        view.dotColor = sharedPreferenceManager.getTextColor()
        view.homeGestureForwarder = homeGestureForwarder
        placeWidgetStack(view)
        return view
    }

    /** Top places the stack between the clock and the media widget; bottom keeps it last in the column. */
    private fun placeWidgetStack(view: WidgetStackView) {
        val parent = binding.clockLayout.parent as ViewGroup
        parent.removeView(view)
        if (sharedPreferenceManager.isWidgetStackOnTop()) {
            parent.addView(view, parent.indexOfChild(binding.clockLayout) + 1)
        } else {
            parent.addView(view)
        }
    }

    private fun startWidgetHost() {
        val host = appWidgetHost ?: return
        if (isWidgetHostListening) return
        try {
            host.startListening()
            isWidgetHostListening = true
        } catch (e: Exception) {
            logger.w("MainActivity", "Failed to start listening for widget updates: ${e.message}")
        }
    }

    private fun stopWidgetHost() {
        if (!isWidgetHostListening) return
        try {
            appWidgetHost?.stopListening()
        } catch (e: Exception) {
            logger.w("MainActivity", "Failed to stop listening for widget updates: ${e.message}")
        }
        isWidgetHostListening = false
    }

    // ============================================
    // Now Playing widget
    // ============================================

    private fun startNowPlaying() {
        if (!sharedPreferenceManager.isNowPlayingEnabled()) return
        val manager = nowPlayingManager ?: NowPlayingManager(
            this,
            lifecycleScope,
            resources.getDimensionPixelSize(R.dimen.now_playing_art_size),
            object : NowPlayingManager.Listener {
                override fun onNowPlayingChanged(info: NowPlayingManager.NowPlayingInfo?) {
                    showNowPlaying(info)
                }
            }
        ).also { nowPlayingManager = it }
        manager.start()
    }

    private fun showNowPlaying(info: NowPlayingManager.NowPlayingInfo?) {
        if (info == null) {
            nowPlayingView?.let {
                it.clear()
                it.visibility = View.GONE
            }
            return
        }
        val view = nowPlayingView ?: inflateNowPlayingView()
        view.bind(info)
        view.visibility = View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun inflateNowPlayingView(): NowPlayingView {
        val view = binding.nowPlayingStub.inflate() as NowPlayingView
        nowPlayingView = view

        view.listener = object : NowPlayingView.Listener {
            override fun onPlayPause() { nowPlayingManager?.playPause() }
            override fun onSkipToNext() { nowPlayingManager?.skipToNext() }
            override fun onSkipToPrevious() { nowPlayingManager?.skipToPrevious() }
            override fun onDismiss() { nowPlayingManager?.dismiss() }
            override fun onOpen() { nowPlayingManager?.openApp() }
        }
        // Keep swipe-up / long-press / double-tap working over the widget, same as homeView,
        // with single tap opening the player.
        val nowPlayingGestureDetector = GestureDetector(this, NowPlayingGestureListener())
        view.homeGestureForwarder = { event ->
            if (!isDrawerOpen && !animations.isInAnim) nowPlayingGestureDetector.onTouchEvent(event)
        }

        uiUtils.setTextColors(view)
        uiUtils.setTextFont(view, uiUtils.resolveTypeface())
        return view
    }

    fun modifyDate(value: String, index: Int) {
        /*Indexes:
        0 = 12h time
        1 = 24h time
        2 = Weather
        3 = Battery level
        4 = Screen time*/
        dateElements[index] = value
        dateText.format12Hour = buildDateLine(0)
        dateText.format24Hour = buildDateLine(1)
    }

    private fun buildDateLine(dateIndex: Int): String {
        val dateFormat = dateElements[dateIndex]
        val extraText = listOf(dateElements[2], dateElements[3], dateElements[4])
            .filter { it.isNotEmpty() }
            .joinToString(" | ")
            .replace("'", "''")

        // TextClock treats its value as a date/time pattern. Quote dynamic text so letters such
        // as the h and m in "2h 20m" are displayed literally instead of being reformatted.
        return if (extraText.isEmpty()) dateFormat else "$dateFormat' | $extraText'"
    }

    fun backToHome(animSpeed: Long = sharedPreferenceManager.getAnimationSpeed()) {
        canLaunchShortcut = true
        showHidden = false
        isDrawerOpen = false

        searchJob?.cancel()
        isResettingSearch = true
        searchView.setText(R.string.empty)
        isResettingSearch = false
        isSearchActive = false

        closeKeyboard()
        animations.showHome(binding.homeView, binding.appView, animSpeed)
        animations.backgroundOut(this@MainActivity, animSpeed)

        // Single delayed operation - no 50ms gaps
        handler.postDelayed({
            try {
                appMenuLinearLayoutManager.setScrollEnabled(true)
                if (::installedApps.isInitialized) {
                    currentFilteredApps = installedApps
                    // Explicitly reset adapter to show full list
                    lifecycleScope.launch {
                        updateMenu(installedApps)
                        refreshAppMenu()
                    }
                }
            } catch (e: Exception) {
                logger.w("MainActivity", "Error in backToHome handler: ${e.message}")
            }
        }, animSpeed)
    }

    private fun closeKeyboard() {
        val imm =
            getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    /**
     * Re-reads installed apps and applies any changes to the drawer. After package events it also
     * adopts the fresh app info and rebinds rows and shortcuts, since an update can rename an app
     * without changing the list.
     */
    @SuppressLint("NotifyDataSetChanged")
    suspend fun refreshAppMenu() {
        try {
            // Don't reset app menu while under a search
            if (isSearchActive) {
                isAppMenuStale = true
                return
            }
            if (::installedApps.isInitialized) {
                isAppMenuStale = false
                val changedPackages = takePendingPackageChanges()
                // Before reading the apps, so sorting caches the updated labels.
                changedPackages.forEach(AppNameResolver::invalidatePackage)
                val updatedApps = appUtils.getInstalledApps(showHidden)
                val packagesChanged = changedPackages.isNotEmpty()
                // Use structural equality check and atomic update pattern
                if (packagesChanged || installedApps.isEmpty() || !listsEqual(installedApps, updatedApps)) {
                    updateMenu(updatedApps)
                    // Atomic update - assign once
                    installedApps = updatedApps
                    currentFilteredApps = updatedApps
                    appSearchIndexDirty = true

                    if (::alphabetIndex.isInitialized && sharedPreferenceManager.isAlphabetIndexEnabled()) {
                        refreshAlphabetIndex(currentFilteredApps)
                    }
                }
                if (packagesChanged) {
                    withContext(Dispatchers.Main) {
                        refreshShortcutLabels()
                        // Drawer rows skip rebinding on name-only changes.
                        appAdapter?.notifyDataSetChanged()
                    }
                }
            }
        } catch (e: Exception) {
            logger.w("MainActivity", "Error in refreshAppMenu: ${e.message}")
        }
    }

    private fun listsEqual(
        list1: List<Triple<LauncherActivityInfo, UserHandle, Int>>,
        list2: List<Triple<LauncherActivityInfo, UserHandle, Int>>
    ): Boolean {
        if (list1.size != list2.size) return false

        for (i in list1.indices) {
            if (list1[i].first.componentName != list2[i].first.componentName || list1[i].second != list2[i].second ||
                list1[i].first.isPaused() != list2[i].first.isPaused()) {
                return false
            }
        }

        return true
    }

    private suspend fun updateMenu(updatedApps: List<Triple<LauncherActivityInfo, UserHandle, Int>>) {
        withContext(Dispatchers.Main) {
            if (isSearchActive) {
                appAdapter?.setApps(updatedApps)
            } else {
                appAdapter?.updateApps(updatedApps)
            }
        }
    }

    /**
     * Keeps the weather reading current while home is visible.
     * Shows the cached reading right away and only fetches once the update interval has
     * elapsed since the last successful fetch, so returning home does not trigger a request.
     * Suspends until cancelled; settings changes wake it via [rescheduleWeather].
     */
    private suspend fun runWeatherUpdates() {
        weatherSettingsChanged.tryReceive()
        while (true) {
            val isGps = sharedPreferenceManager.isWeatherGPS()
            val location = sharedPreferenceManager.getWeatherLocation()
            if (!sharedPreferenceManager.isWeatherEnabled() || (!isGps && location.isEmpty())) {
                modifyDate("", 2)
                weatherSettingsChanged.receive()
                continue
            }

            val cacheKey = WeatherCache.keyFor(isGps, location, sharedPreferenceManager.getTempUnits())
            val cached = weatherCache.get(cacheKey)
            modifyDate(cached?.text.orEmpty(), 2)

            val waitMs = WeatherSchedule.delayUntilNextFetch(
                lastSuccessMs = cached?.fetchedAtMs,
                lastFailureMs = lastWeatherFailureMs,
                failures = weatherFailures,
                nowMs = System.currentTimeMillis(),
                intervalMs = sharedPreferenceManager.getWeatherUpdateIntervalMs()
            )
            if (waitMs > 0 && withTimeoutOrNull(waitMs) { weatherSettingsChanged.receive() } != null) {
                continue
            }
            updateWeather(cacheKey)
        }
    }

    /**
     * Fetches the weather and caches it under [cacheKey].
     * On failure the displayed reading is kept and the fetch time is not advanced.
     */
    private suspend fun updateWeather(cacheKey: String) {
        val temp = withContext(Dispatchers.IO) {
            if (sharedPreferenceManager.isWeatherGPS()) {
                weatherSystem.updateGpsLocation()
                // updateGpsLocation swallows cancellation, so check before starting the request.
                ensureActive()
            }
            weatherSystem.getTemp()
        }
        val now = System.currentTimeMillis()
        // Empty here means GPS has no fix yet, since a missing manual location is handled by the caller.
        if (temp.isNullOrEmpty()) {
            weatherFailures++
            lastWeatherFailureMs = now
            return
        }
        weatherFailures = 0
        lastWeatherFailureMs = null
        weatherCache.put(cacheKey, temp, now)
        modifyDate(temp, 2)
    }

    /**
     * Re-evaluates the weather schedule after a settings change.
     * The loop only runs while home is visible, so changes made in settings apply on return.
     */
    private fun rescheduleWeather() {
        weatherFailures = 0
        lastWeatherFailureMs = null
        weatherSettingsChanged.trySend(Unit)
    }

    private suspend fun updateScreenTime() {
        if (!sharedPreferenceManager.isScreenTimeEnabled()) {
            curboxApiClient.disconnect()
            modifyDate("", 4)
            return
        }

        if (!curboxApiClient.connect()) {
            modifyDate("", 4)
            return
        }

        val minutes = curboxApiClient.getTodayScreenTimeMinutes()
        if (minutes == null) {
            modifyDate("", 4)
            return
        }
        val duration = if (minutes >= 60) {
            getString(R.string.screen_time_hours_minutes, minutes / 60, minutes % 60)
        } else {
            getString(R.string.screen_time_minutes, minutes)
        }
        modifyDate(getString(R.string.screen_time_display, duration), 4)
    }

    private fun setupApps() {
        lifecycleScope.launch(Dispatchers.Default) {
            installedApps = appUtils.getInstalledApps()
            currentFilteredApps = installedApps
            val newApps = installedApps.toMutableList()

            // Pre-build search index so the first keystroke doesn't hitch.
            appSearchIndex = buildAppSearchIndex(installedApps)
            appSearchIndexDirty = false

            setupAppRecycler(newApps)

            setupSearch()
            if (sharedPreferenceManager.areContactsEnabled()) {
                setupContactRecycler()
            }

            setupInternetSearch()

            setupAlphabetIndex()
        }
    }

    private fun setupAlphabetIndex() {
        if (!sharedPreferenceManager.isAlphabetIndexEnabled()) {
            return
        }
        lifecycleScope.launch(Dispatchers.Main) {
            alphabetIndex.setOnLetterSelectedListener { letter ->
                scrollToLetter(letter, currentFilteredApps)
            }
            refreshAlphabetIndex(currentFilteredApps)
        }
    }

    private fun refreshAlphabetIndex(apps: List<Triple<LauncherActivityInfo, UserHandle, Int>>) {
        val availableLetters = buildAlphabetIndexLetters(apps)
        alphabetIndex.post {
            if (!sharedPreferenceManager.isAlphabetIndexEnabled() || menuView.displayedChild != 0) {
                alphabetIndex.visibility = View.GONE
                return@post
            }
            alphabetIndex.setTextColor(sharedPreferenceManager.getTextColor())
            alphabetIndex.setTextShadow(sharedPreferenceManager.isTextShadowEnabled())
            alphabetIndex.setAvailableLetters(availableLetters)
            setAlphabetIndexPosition()
            alphabetIndex.visibility = if (availableLetters.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun buildAlphabetIndexLetters(apps: List<Triple<LauncherActivityInfo, UserHandle, Int>>): Set<String> {
        if (appSearchIndexDirty || appSearchIndex.size != apps.size) {
            appSearchIndex = buildAppSearchIndex(apps)
            appSearchIndexDirty = false
        }
        return appSearchIndex.asSequence()
            .map { getAlphabetIndexKey(it.cleaned) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun getAlphabetIndexKey(name: String): String {
        val trimmed = name.trimStart()
        if (trimmed.isEmpty()) return "#"
        val firstChar = trimmed.first()
        return if (firstChar.isLetter()) firstChar.uppercaseChar().toString() else "#"
    }

    private fun setAlphabetIndexPosition() {
        val position = sharedPreferenceManager.getAlphabetIndexPosition()
        val layoutParams = alphabetIndex.layoutParams as android.widget.FrameLayout.LayoutParams

        when (position) {
            "left" -> layoutParams.gravity = android.view.Gravity.START
            "right" -> layoutParams.gravity = android.view.Gravity.END
        }
        alphabetIndex.layoutParams = layoutParams
    }

    private fun scrollToLetter(letter: String, apps: List<Triple<LauncherActivityInfo, UserHandle, Int>>) {
        if (appSearchIndexDirty || appSearchIndex.size != apps.size) {
            appSearchIndex = buildAppSearchIndex(apps)
            appSearchIndexDirty = false
        }
        val isNonLetter = letter == "#"
        val targetPosition = appSearchIndex.indexOfFirst {
            val key = getAlphabetIndexKey(it.cleaned)
            if (isNonLetter) key == "#" else key == letter
        }

        if (targetPosition >= 0) {
            appRecycler.scrollToPosition(appAdapter?.adapterPositionOfApp(targetPosition) ?: targetPosition)
        }
    }

    private fun setupInternetSearch() {
        uiUtils.setImageColor(internetSearch)
        internetSearch.setOnClickListener {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, searchView.text.toString())
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                logger.w("MainActivity", "No browser app found for web search")
                Toast.makeText(this@MainActivity, "No browser app found.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun setupAppRecycler(newApps: MutableList<Triple<LauncherActivityInfo, UserHandle, Int>>) {
        appAdapter = AppMenuAdapter(this@MainActivity, binding, newApps, this@MainActivity, this@MainActivity, this@MainActivity, this@MainActivity, launcherApps)
        appMenuLinearLayoutManager.stackFromEnd = true
        appRecycler = binding.appRecycler
        withContext(Dispatchers.Main) {
            appAdapter?.setFolders(folders)
            appRecycler.layoutManager = appMenuLinearLayoutManager
            appRecycler.edgeEffectFactory = appMenuEdgeFactory
            appRecycler.adapter = appAdapter

        }

        setupRecyclerListener(appRecycler, appMenuLinearLayoutManager)
    }

    // Inform the layout manager of scroll states to calculate whether the menu is on the top
    private fun setupRecyclerListener(recycler: RecyclerView, layoutManager: AppMenuLinearLayoutManager) {
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    layoutManager.setScrollInfo()
                }
            }
        })
    }

    private fun setupContactRecycler() {
        uiUtils.setImageColor(searchSwitcher)

        contactAdapter = ContactsAdapter(this, mutableListOf(), this@MainActivity, this@MainActivity)
        contactMenuLinearLayoutManager.stackFromEnd = true
        contactRecycler = binding.contactRecycler
        contactRecycler.layoutManager = contactMenuLinearLayoutManager
        contactRecycler.edgeEffectFactory = appMenuEdgeFactory
        contactRecycler.adapter = contactAdapter
        setupRecyclerListener(contactRecycler, contactMenuLinearLayoutManager)

        searchSwitcher.setOnClickListener {
            switchMenus()
        }
    }

    fun switchMenus() {
        menuView.showNext()
        when (menuView.displayedChild) {
            0 -> {
                setAppViewDetails()
            }

            1 -> {
                setContactViewDetails()
            }
        }
    }

    private fun setAppViewDetails() {
        lifecycleScope.launch {
            // Cancel any ongoing search job when switching views
            searchJob?.cancel()
            withContext(Dispatchers.Default) {
                filterItems(searchView.text.toString())
            }
        }
        searchSwitcher.setImageDrawable(
            ResourcesCompat.getDrawable(
                resources,
                R.drawable.contacts_24px,
                null
            )
        )
        searchSwitcher.contentDescription = getString(R.string.switch_to_contacts)
        refreshAlphabetIndex(currentFilteredApps)
    }

    private fun setContactViewDetails() {
        lifecycleScope.launch {
            // Cancel any ongoing search job when switching views
            searchJob?.cancel()
            withContext(Dispatchers.Default) {
                filterItems(searchView.text.toString())
            }
        }
        searchSwitcher.setImageDrawable(ResourcesCompat.getDrawable(resources, R.drawable.apps_24px, null))
        searchSwitcher.contentDescription = getString(R.string.switch_to_apps)
        alphabetIndex.visibility = View.GONE
    }

    private fun getContacts(filterString: String): MutableList<Pair<String, Int>> {
        val contacts = mutableListOf<Pair<String, Int>>()

        try {
            val contentResolver: ContentResolver = contentResolver

            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME
            )

            val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$filterString%")

            val cursor: Cursor? = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                while (it.moveToNext()) {
                    val name = it.getStringOrNull(nameIndex)
                    val id = it.getStringOrNull(idIndex)?.toInt()
                    if (name != null && id != null) {
                        contacts.add(Pair(name, id))
                    }
                }
            }
        } catch (e: SecurityException) {
            logger.w("MainActivity", "Permission denied for contacts: ${e.message}")
        } catch (e: Exception) {
            logger.e("MainActivity", "Error getting contacts", e)
        }
        return contacts
    }

    private suspend fun updateContacts(filterString: String) {
        isSearchActive = filterString.isNotEmpty()
        val contacts = getContacts(filterString)
        withContext(Dispatchers.Main) {
            contactAdapter?.updateContacts(contacts)
        }
    }

    private fun setupSearch() {
        binding.appView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->

            if (bottom - top > oldBottom - oldTop) {
                // If keyboard is closed, remove cursor from the search bar
                searchView.clearFocus()
                menuTitle.clearFocus()
            } else if (bottom - top < oldBottom - oldTop && isInitialOpen) {
                isInitialOpen = false
                appRecycler.scrollToPosition(0)
            }

        }

        searchView.addTextChangedListener(object :
            TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Cancel previous search job to implement debouncing
                searchJob?.cancel()
            }

            override fun afterTextChanged(s: Editable?) {
                searchJob = lifecycleScope.launch {
                    delay(50) // 50ms debounce delay - prevents excessive filtering during fast typing
                    while (isResettingSearch) {
                        delay(16) // Wait for reset to complete
                    }
                    withContext(Dispatchers.Default) {
                        filterItems(s.toString())
                    }
                }
            }
        })
    }

    private data class FilterResult(
        val filteredApps: List<Triple<LauncherActivityInfo, UserHandle, Int>>?,
        val isEmptyQuery: Boolean
    )

    /**
     * Filters app/contact lists based on the search query.
     * Updates the currently displayed list and alphabet index accordingly.
     * Uses debouncing to prevent excessive filtering during fast typing.
     *
     * @param query The search string to filter by
     */
    private suspend fun filterItems(query: String?) {
        val cleanQuery = stringUtils.cleanString(query)
        // Guard against uninitialized menuView
        val displayedChild = if (::menuView.isInitialized) menuView.displayedChild else 0
        when (displayedChild) {
            0 -> {
                if (!::installedApps.isInitialized) return
                val appsToFilter = installedApps
                val result = withContext(Dispatchers.Default) {
                    getFilteredApps(cleanQuery, appsToFilter)
                }
                withContext(Dispatchers.Main) {
                    if (result.isEmptyQuery) {
                        isSearchActive = false
                        currentFilteredApps = installedApps
                        updateMenu(installedApps)
                        refreshAlphabetIndex(currentFilteredApps)
                        if (isAppMenuStale) refreshAppMenu()
                    } else if (result.filteredApps != null) {
                        isSearchActive = true
                        applySearchFilter(result.filteredApps)
                    }
                }
            }

            1 -> {
                if (sharedPreferenceManager.areContactsEnabled() && cleanQuery != null) {
                    updateContacts(cleanQuery)
                }
            }
        }
    }

    /**
     * Performs fuzzy or exact matching of apps against the search query.
     * Results are sorted by relevance: exact matches first, then prefix matches,
     * then contains matches. Within each bucket, alphabetical order is preserved.
     *
     * @param cleanQuery Sanitized lowercase search query
     * @param updatedApps List of apps to filter through
     * @return FilterResult containing prioritized matching apps or empty query indicator
     */
    private suspend fun getFilteredApps(
        cleanQuery: String?,
        updatedApps: List<Triple<LauncherActivityInfo, UserHandle, Int>>
    ): FilterResult {
        if (cleanQuery.isNullOrEmpty()) {
            return FilterResult(null, true)
        } else {
            if (appSearchIndexDirty || appSearchIndex.size != updatedApps.size) {
                appSearchIndex = buildAppSearchIndex(updatedApps)
                appSearchIndexDirty = false
            }

            val queryLower = cleanQuery.lowercase()

            val fuzzyPattern = if (sharedPreferenceManager.isFuzzySearchEnabled()) {
                stringUtils.getFuzzyPattern(cleanQuery)
            } else {
                null
            }

            val exactMatches = mutableListOf<Triple<LauncherActivityInfo, UserHandle, Int>>()
            val otherMatches = mutableListOf<Triple<LauncherActivityInfo, UserHandle, Int>>()

            //List containing apps whose name starts with the specified query
            val startWithMatches = mutableListOf<Triple<LauncherActivityInfo, UserHandle, Int>>()

            for (entry in appSearchIndex) {
                if (entry.cleaned.isEmpty()) continue

                val fuzzyMatch = fuzzyPattern?.containsMatchIn(entry.cleaned) == true
                val contains = entry.cleanedLower.contains(queryLower)
                if (!contains && !fuzzyMatch) continue

                // Each matching app is placed into a priority bucket based on how closely it matches the query
                when{
                    entry.cleanedLower == queryLower -> exactMatches.add(entry.item)
                    entry.cleanedLower.startsWith(queryLower) -> startWithMatches.add(entry.item)
                    else -> otherMatches.add(entry.item)
                }

            }

            // Results are ordered in the following order:
            // 1. exactMatches - app name that is equal to the query
            // 2. startWithMatches - app name starts with the query
            // 3. otherMatches - app name contains the query
            // -- Within each bucket the alphabetic order is preserved --
            return FilterResult(exactMatches + startWithMatches + otherMatches, false)
        }
    }

    private suspend fun applySearchFilter(newFilteredApps: List<Triple<LauncherActivityInfo, UserHandle, Int>>) {
        // Guard against uninitialized views - check before accessing
        val shouldAutoLaunch = if (::menuView.isInitialized && ::appRecycler.isInitialized) {
            sharedPreferenceManager.isAutoLaunchEnabled() && menuView.displayedChild == 0 && appAdapter?.shortcutTextView == null && newFilteredApps.size == 1
        } else {
            false
        }
        
        if (shouldAutoLaunch) {
            appUtils.launchApp(newFilteredApps[0].first.componentName, newFilteredApps[0].second)
        } else {
            updateMenu(newFilteredApps)
            currentFilteredApps = newFilteredApps
            if (::alphabetIndex.isInitialized) {
                refreshAlphabetIndex(currentFilteredApps)
            }
            if (::appRecycler.isInitialized) {
                appRecycler.scrollToPosition(0)
            }
        }
    }

    suspend fun applySearch() {
        // Cancel any ongoing search job
        searchJob?.cancel()
        withContext(Dispatchers.Default) {
            filterItems(searchView.text.toString())
        }
    }

    fun disableAppMenuScroll() {
        appMenuLinearLayoutManager.setScrollEnabled(false)
        appRecycler.layoutManager = appMenuLinearLayoutManager
    }

    fun enableAppMenuScroll() {
        appMenuLinearLayoutManager.setScrollEnabled(true)
        appRecycler.layoutManager = appMenuLinearLayoutManager
    }

    // On home key or swipe, return to home screen
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        backToHome()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.homeView.isVisible && event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            val unicodeChar = event.unicodeChar

            if (unicodeChar != 0 && !KeyEvent.isModifierKey(keyCode)) {
                val char = unicodeChar.toChar()
                if (char.isLetterOrDigit()) {
                    openAppMenuWithChar(char.toString())
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openAppMenuWithChar(char: String) {
        appAdapter?.shortcutTextView = null
        contactAdapter?.shortcutTextView = null
        menuTitle.visibility = View.GONE
        uiUtils.setWebSearchVisibility(internetSearch)

        searchView.setText(char)
        searchView.setSelection(searchView.text?.length ?: 0)

        toAppMenu(showKeyboard = true)
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            launcherApps.unregisterCallback(packageCallback)
            unregisterBatteryReceiver()
            unregisterNotificationReceiver()
            nowPlayingManager?.stop()
            stopWidgetHost()
            preferences.unregisterOnSharedPreferenceChangeListener(this)
            searchJob?.cancel()
            curboxApiClient.disconnect()
        } catch (e: Exception) {
            logger.w("MainActivity", "Error during onDestroy cleanup: ${e.message}")
        }
        logger.i("MainActivity", "MainActivity destroyed")
    }

    private fun registerNotificationReceiver() {
        if (notificationCollectionJob == null) {
            try {
                notificationCollectionJob = lifecycleScope.launch {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        NotificationEventBus.notificationsChanged.collect {
                            updateNotificationDots()
                        }
                    }
                }
            } catch (e: Exception) {
                notificationCollectionJob = null
                logger.w("MainActivity", "Failed to register notification receiver: ${e.message}")
            }
        }
    }

    private fun unregisterNotificationReceiver() {
        notificationCollectionJob?.cancel()
        notificationCollectionJob = null
    }

    private fun updateNotificationDots() {
        if (!sharedPreferenceManager.isNotificationDotsEnabled()) return

        val notificationListener = NotificationListener.getInstance()
        if (notificationListener == null) {
            if (!NotificationListener.isEnabled(this)) {
                requestNotificationPermission()
            }
            return
        }

        val packagesWithNotifications = notificationListener.getPackagesWithNotifications()
        val textColor = sharedPreferenceManager.getTextColor()

        for ((i, id) in SHORTCUT_IDS.withIndex()) {
            val textView = findViewById<TextView>(id) ?: continue
            val savedView = sharedPreferenceManager.getShortcut(i)

            if (savedView == null || savedView.getOrNull(3)?.toBoolean() == true) continue
            val componentName = savedView.getOrNull(0) ?: continue
            if (componentName == "e") continue

            val packageName = if (componentName.contains("/")) componentName.substringBefore("/") else componentName
            val dotDrawable = if (packagesWithNotifications.contains(packageName)) {
                ResourcesCompat.getDrawable(resources, R.drawable.notification_dot, null)?.mutate()
            } else null

            dotDrawable?.setTint(textColor)
            textView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                textView.compoundDrawablesRelative.getOrNull(0),
                null,
                dotDrawable,
                null
            )
        }
    }

    private fun requestNotificationPermission() {
        NotificationListener.requestPermission(this)
    }

    override fun onStart() {
        super.onStart()
        // Keyboard is sometimes open when going back to the app, so close it.
        closeKeyboard()
        startNowPlaying()
        if (sharedPreferenceManager.isWidgetStackEnabled()) startWidgetHost()
        (getSystemService(AUDIO_SERVICE) as AudioManager).registerAudioDeviceCallback(headphoneCallback, handler)
    }

    override fun onStop() {
        super.onStop()
        // Drop every media session callback while not visible: zero idle cost.
        nowPlayingManager?.stop()
        // Same for widget updates: providers stop pushing RemoteViews to a stopped host.
        stopWidgetHost()
        (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(headphoneCallback)
        // Package changes stay recorded and are applied by the refresh in onResume.
        packageRefreshJob?.cancel()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Close keyboard and immediately restore full layout height.
            // onStart() fires before the window has focus, so hideSoftInputFromWindow
            // can't close a keyboard owned by another app. onWindowFocusChanged is
            // called once we actually own the window, making this reliable.
            closeKeyboard()
            binding.root.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.root.requestLayout()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onResume() {
        super.onResume()
        if (!permissionUtils.hasPermission(this@MainActivity, Manifest.permission.READ_CONTACTS)) {
            sharedPreferenceManager.setContactsEnabled(false)
        }
        if (!permissionUtils.hasPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            sharedPreferenceManager.setWeatherGPS(false)
        }
        // Also catches package events missed while stopped; backToHome refreshes the menu itself.
        packageRefreshJob?.cancel()
        if (returnAllowed) {
            backToHome(0)
        } else {
            lifecycleScope.launch { refreshAppMenu() }
        }
        returnAllowed = true
        appAdapter?.notifyDataSetChanged()

        updateNotificationDots()

        updateDefaultLauncherBanner()
    }

    private fun updateDefaultLauncherBanner() {
        val defaultLauncherBanner = findViewById<LinearLayout>(R.id.defaultLauncherBanner)
        if (!isDefaultLauncher()) {
            defaultLauncherBanner?.visibility = View.VISIBLE
        } else {
            defaultLauncherBanner?.visibility = View.GONE
        }
    }

    private fun isDefaultLauncher(): Boolean {
        // The HOME role is what Settings shows; the legacy preferred activity can be dropped
        // (e.g. after this launcher crashes) while the role is still held.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == packageName
    }

    override fun onItemClick(appInfo: LauncherActivityInfo, userHandle: UserHandle) {
        appUtils.launchApp(appInfo.componentName, userHandle)
    }

    override fun onShortcut(
        appInfo: LauncherActivityInfo,
        userHandle: UserHandle,
        textView: TextView,
        userProfile: Int,
        shortcutView: TextView,
        shortcutIndex: Int
    ) {
        if (userProfile != 0) {
            shortcutView.setCompoundDrawablesWithIntrinsicBounds(
                ResourcesCompat.getDrawable(resources, R.drawable.ic_work_app, null),
                null,
                null,
                null
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                shortcutView.compoundDrawables[0]?.colorFilter =
                    BlendModeColorFilter(sharedPreferenceManager.getTextColor(), BlendMode.SRC_ATOP)
                shortcutView.compoundDrawables[2]?.colorFilter =
                    BlendModeColorFilter(sharedPreferenceManager.getTextColor(), BlendMode.SRC_ATOP)
            } else {
                shortcutView.compoundDrawables[0]?.colorFilter =
                    PorterDuffColorFilter(sharedPreferenceManager.getTextColor(), PorterDuff.Mode.SRC_ATOP)
                shortcutView.compoundDrawables[2]?.colorFilter =
                    PorterDuffColorFilter(sharedPreferenceManager.getTextColor(), PorterDuff.Mode.SRC_ATOP)
            }
        } else {
            shortcutView.setCompoundDrawablesWithIntrinsicBounds(ResourcesCompat.getDrawable(resources, R.drawable.ic_empty, null), null, null, null)
        }

        shortcutView.text = textView.text.toString()
        uiUtils.setPausedStyle(shortcutView, appInfo.isPaused())
        shortcutView.setOnClickListener {
            appUtils.launchApp(appInfo.componentName, userHandle)
        }
        sharedPreferenceManager.setShortcut(
            shortcutIndex,
            shortcutView.text,
            appInfo.componentName.flattenToString(),
            userProfile
        )
        uiUtils.setDrawables(shortcutView, sharedPreferenceManager.getShortcutAlignment())
        backToHome()
    }

    /**
     * Called when an app item is long-pressed, showing the app action bottom sheet.
     * Allows users to pin, hide, rename apps or view app info.
     */
    override fun onItemLongClick(
        appInfo: LauncherActivityInfo,
        userHandle: UserHandle,
        userProfile: Int
    ) {
        val bottomSheet = AppActionBottomSheet.newInstance(appInfo, userHandle, userProfile, this)
        bottomSheet.show(supportFragmentManager, AppActionBottomSheet.TAG)
    }

    /**
     * Pins or unpins an app, affecting its display position in the app menu.
     * Pinned apps appear first in the list for quick access.
     */
    override fun onPinApp(appActivity: LauncherActivityInfo, workProfile: Int) {
        val componentName = appActivity.componentName.flattenToString()
        sharedPreferenceManager.setPinnedApp(componentName, workProfile)
        val isPinned = sharedPreferenceManager.isAppPinned(componentName, workProfile)
        logger.i("MainActivity", "App ${appActivity.label} ${if (isPinned) "pinned" else "unpinned"}")
        lifecycleScope.launch {
            refreshAppMenu()
            // The refresh skips unchanged orderings; re-push so pin icons and the Pinned section update.
            updateMenu(currentFilteredApps)
        }
    }

    override fun onAppInfo(appActivity: LauncherActivityInfo, userHandle: UserHandle) {
        try {
            launcherApps.startAppDetailsActivity(
                appActivity.componentName,
                userHandle,
                null,
                null
            )
        } catch (e: SecurityException) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", appActivity.applicationInfo.packageName, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e2: Exception) {
                logger.e("MainActivity", "Failed to open app details via Intent fallback", e2)
                Toast.makeText(this, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            logger.e("MainActivity", "Failed to open app details", e)
            Toast.makeText(this, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUninstallApp(appActivity: LauncherActivityInfo, userHandle: UserHandle) {
        logger.i("MainActivity", "Uninstalling app: ${appActivity.applicationInfo.packageName}")
        val intent = Intent(Intent.ACTION_DELETE)
        intent.data = "package:${appActivity.applicationInfo.packageName}".toUri()
        intent.putExtra(Intent.EXTRA_USER, userHandle)
        startActivity(intent)
        returnAllowed = false
    }

    override fun onRenameApp(appActivity: LauncherActivityInfo, userHandle: UserHandle, workProfile: Int) {
        val componentName = appActivity.componentName.flattenToString()
        withDrawerRow(findAppPosition(appActivity, workProfile)) { holder ->
            startRenameMode(
                holder,
                onSave = { newName ->
                    sharedPreferenceManager.setAppName(componentName, workProfile, newName)
                    logger.i("MainActivity", "App renamed from '${appActivity.label}' to '$newName'")
                    appSearchIndexDirty = true
                    lifecycleScope.launch { applySearch() }
                    refreshShortcutLabels()
                },
                onReset = {
                    sharedPreferenceManager.resetAppName(componentName, workProfile)
                    appSearchIndexDirty = true
                    lifecycleScope.launch { applySearch() }
                    refreshShortcutLabels()
                }
            )
        }
    }

    /** Runs [action] on the drawer row at [position], scrolling it into view first if it was recycled. */
    private fun withDrawerRow(position: Int, action: (AppMenuAdapter.AppViewHolder) -> Unit) {
        if (position == -1) return
        val holder = appRecycler.findViewHolderForAdapterPosition(position) as? AppMenuAdapter.AppViewHolder
        if (holder != null) {
            action(holder)
            return
        }
        appRecycler.layoutManager?.scrollToPosition(position)
        appRecycler.post {
            val retryHolder = appRecycler.findViewHolderForAdapterPosition(position) as? AppMenuAdapter.AppViewHolder
            if (retryHolder != null) {
                action(retryHolder)
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.rename_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var renameLayoutListener: View.OnLayoutChangeListener? = null

    /**
     * Swaps a drawer row's label for its inline edit field. Closing the keyboard cancels.
     * [onSave] persists a non-blank name; [onReset] restores the default name, and the reset
     * button is hidden when there is no default. The row is rebound afterwards to show the result.
     */
    private fun startRenameMode(holder: AppMenuAdapter.AppViewHolder, onSave: (String) -> Unit, onReset: (() -> Unit)?) {
        val textView = holder.textView
        val editLayout = holder.editView
        val editText = holder.editText
        val resetButton = editLayout.findViewById<AppCompatButton>(R.id.reset)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val searchEnabled = sharedPreferenceManager.isSearchEnabled()

        disableAppMenuScroll()
        textView.visibility = View.INVISIBLE
        animations.fadeViewIn(editLayout)
        resetButton.isVisible = onReset != null
        searchView.visibility = if (searchEnabled) View.INVISIBLE else View.GONE
        editText.requestFocus()
        handler.postDelayed({ imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT) }, 100)

        fun exitRenameMode() {
            renameLayoutListener?.let { binding.root.removeOnLayoutChangeListener(it) }
            renameLayoutListener = null
            editLayout.clearFocus()
            searchView.visibility = if (searchEnabled) View.VISIBLE else View.GONE
            enableAppMenuScroll()
        }

        fun commit(change: () -> Unit) {
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
            exitRenameMode()
            change()
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let {
                appAdapter?.notifyItemChanged(it)
            }
        }

        renameLayoutListener = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            // Root grew back: the keyboard was dismissed without confirming.
            if (bottom - top > oldBottom - oldTop) {
                exitRenameMode()
                animations.fadeViewOut(editLayout)
                animations.fadeViewIn(textView)
            }
        }.also { binding.root.addOnLayoutChangeListener(it) }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            if (editText.text.isNullOrBlank()) {
                Toast.makeText(this@MainActivity, getString(R.string.empty_rename), Toast.LENGTH_SHORT).show()
            } else {
                val newName = editText.text.toString()
                commit { onSave(newName) }
            }
            true
        }

        resetButton.setOnClickListener { onReset?.let { commit(it) } }
    }

    private fun findAppPosition(appActivity: LauncherActivityInfo, workProfile: Int): Int {
        val appsToSearch = currentFilteredApps
        for (i in 0 until appsToSearch.size) {
            val tuple = appsToSearch.getOrNull(i)
            if (tuple?.first?.componentName == appActivity.componentName && tuple.third == workProfile) {
                return appAdapter?.adapterPositionOfApp(i) ?: i
            }
        }
        return -1
    }

    override fun onHideApp(appActivity: LauncherActivityInfo, workProfile: Int) {
        logger.i("MainActivity", "Hiding app: ${appActivity.label}")
        lifecycleScope.launch {
            sharedPreferenceManager.setAppHidden(appActivity.componentName.flattenToString(), workProfile, true)
            refreshAppMenu()
        }
    }

    // ============================================
    // Folders
    // ============================================

    private fun showFolders(newFolders: List<AppFolder>) {
        folders = newFolders.sortedBy { it.name.lowercase() }
        appAdapter?.setFolders(folders)
    }

    private fun saveFolders(newFolders: List<AppFolder>) {
        sharedPreferenceManager.setFolders(newFolders)
        showFolders(newFolders)
    }

    private fun dialogContext() = ContextThemeWrapper(
        this,
        com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar
    )

    private fun folderDialog() = MaterialAlertDialogBuilder(dialogContext())

    /** Lists the folder's apps in drawer order; hidden or uninstalled members are skipped. */
    private fun folderApps(folder: AppFolder): List<Triple<LauncherActivityInfo, UserHandle, Int>> {
        if (!::installedApps.isInitialized) return emptyList()
        val members = folder.apps.toHashSet()
        return installedApps.filter { (it.first.componentName.flattenToString() to it.third) in members }
    }

    private fun openFolder(id: Long) {
        val folder = folders.firstOrNull { it.id == id }
        if (folder == null) {
            Toast.makeText(this, getString(R.string.folder_missing), Toast.LENGTH_SHORT).show()
            return
        }
        onFolderClick(folder)
    }

    override fun onFolderClick(folder: AppFolder) {
        val members = folderApps(folder)
        val builder = folderDialog().setTitle(folder.name)
        if (members.isEmpty()) {
            builder.setMessage(R.string.folder_empty)
        } else {
            val names = members.map { (info, _, profile) ->
                sharedPreferenceManager.getAppName(
                    info.componentName.flattenToString(),
                    profile,
                    AppNameResolver.resolveBaseLabel(this, info)
                ) ?: info.label
            }.toTypedArray()
            builder.setItems(names) { _, which -> onItemClick(members[which].first, members[which].second) }
        }
        builder.show()
    }

    override fun onFolderLongClick(folder: AppFolder) {
        AppActionBottomSheet.newInstance(folder, this).show(supportFragmentManager, AppActionBottomSheet.TAG)
    }

    override fun onRenameFolder(folder: AppFolder) {
        withDrawerRow(appAdapter?.adapterPositionOfFolder(folder.id) ?: -1) { holder ->
            startRenameMode(holder, onSave = { input ->
                val name = AppFolder.sanitizeName(input)
                if (name.isNotEmpty()) saveFolders(folders.map { if (it.id == folder.id) it.copy(name = name) else it })
                refreshShortcutLabels()
            }, onReset = null)
        }
    }

    override fun onDeleteFolder(folder: AppFolder) {
        folderDialog()
            .setTitle(R.string.delete_folder)
            .setMessage(getString(R.string.delete_folder_confirm, folder.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                saveFolders(folders.filter { it.id != folder.id })
            }
            .setNegativeButton(R.string.confirm_no, null)
            .show()
    }

    override fun onFolderShortcut(folder: AppFolder, shortcutView: TextView, shortcutIndex: Int) {
        val setting = ShortcutSetting(AppFolder.componentKey(folder.id), 0, folder.name, false)
        sharedPreferenceManager.setShortcut(shortcutIndex, setting.label, setting.componentName, setting.profile)
        setShortcutSetup(shortcutView, setting)
        uiUtils.setDrawables(shortcutView, sharedPreferenceManager.getShortcutAlignment())
        backToHome()
    }

    /**
     * With no folders, goes straight to naming a new one. Otherwise shows every folder with the
     * app's current membership checked; toggling applies immediately, so this also removes apps.
     */
    override fun onAddToFolder(appActivity: LauncherActivityInfo, workProfile: Int) {
        val app = appActivity.componentName.flattenToString() to workProfile
        val createFolderWithApp = {
            promptNewFolderName { name ->
                saveFolders(folders + AppFolder(System.currentTimeMillis(), name, listOf(app)))
            }
        }
        val snapshot = folders
        if (snapshot.isEmpty()) {
            createFolderWithApp()
            return
        }
        val checked = BooleanArray(snapshot.size) { app in snapshot[it].apps }
        folderDialog()
            .setTitle(R.string.add_to_folder)
            .setMultiChoiceItems(snapshot.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                val id = snapshot[which].id
                saveFolders(folders.map { folder ->
                    if (folder.id != id) folder
                    else folder.copy(apps = if (isChecked) (folder.apps + app).distinct() else folder.apps - app)
                })
            }
            .setNeutralButton(R.string.new_folder) { _, _ -> createFolderWithApp() }
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun promptNewFolderName(onName: (String) -> Unit) {
        val context = dialogContext()
        val input = EditText(context).apply {
            hint = getString(R.string.folder_name)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val dialog = folderDialog()
            .setTitle(R.string.new_folder)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.confirm_no, null)
            .create()
        dialog.setOnShowListener {
            // Replaces the default listener so an empty name keeps the dialog open.
            val save = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            save.setOnClickListener {
                val name = AppFolder.sanitizeName(input.text.toString())
                if (name.isEmpty()) {
                    input.error = getString(R.string.empty_rename)
                } else {
                    onName(name)
                    dialog.dismiss()
                }
            }
            input.setOnEditorActionListener { _, actionId, _ ->
                (actionId == EditorInfo.IME_ACTION_DONE).also { if (it) save.performClick() }
            }
            input.requestFocus()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    /**
     * Handles gesture detection for home screen interactions.
     * Supports swipe gestures (up/down/left/right), long press for settings,
     * and double tap for configured actions or screen lock.
     *
     * Swipe up opens the app menu, swipe down expands notifications,
     * swipe left/right launch configured apps, and double tap can lock screen
     * or launch a configured app based on user preferences.
     */
    open inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        @SuppressLint("WrongConstant")
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (animations.isInAnim) {
                return false
            }
            if (e1 != null) {
                val deltaY = e2.y - e1.y
                val deltaX = e2.x - e1.x

                // Swipe up
                if (deltaY < -swipeThreshold && abs(velocityY) > swipeVelocityThreshold) {
                    canLaunchShortcut = false
                    openAppMenu()
                }

                // Swipe down
                else if (deltaY > swipeThreshold && abs(velocityY) > swipeVelocityThreshold) {
                    try {
                        val statusBarService = getSystemService(STATUS_BAR_SERVICE)
                        val statusBarManager: Class<*> = Class.forName("android.app.StatusBarManager")
                        val expandMethod: Method = statusBarManager.getMethod("expandNotificationsPanel")
                        expandMethod.invoke(statusBarService)
                    } catch (e: ReflectiveOperationException) {
                        logger.w("MainActivity", "Unable to expand the notification panel: ${e.message}")
                    } catch (e: SecurityException) {
                        logger.w("MainActivity", "Notification panel access denied: ${e.message}")
                    }
                }

                // Swipe left
                else if (deltaX < 0 && abs(deltaX) > swipeThreshold && abs(velocityX) > swipeVelocityThreshold && sharedPreferenceManager.isGestureEnabled(
                        "left"
                    )
                ) {
                    val (activity, profileIndex) = leftSwipeActivity
                    if (activity != null && profileIndex != null && profileIndex in launcherApps.profiles.indices) {
                        canLaunchShortcut = false
                        try {
                            appUtils.launchApp(activity.componentName, launcherApps.profiles[profileIndex])
                        } catch (e: Exception) {
                            logger.e("MainActivity", "Failed to launch left swipe app", e)
                            Toast.makeText(this@MainActivity, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        logger.w("MainActivity", "Left swipe gesture failed: no app configured")
                        Toast.makeText(this@MainActivity, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
                    }
                }


                // Swipe right
                else if (deltaX > 0 && abs(deltaX) > swipeThreshold && abs(velocityX) > swipeVelocityThreshold && sharedPreferenceManager.isGestureEnabled(
                        "right"
                    )
                ) {
                    val (activity, profileIndex) = rightSwipeActivity
                    if (activity != null && profileIndex != null && profileIndex in launcherApps.profiles.indices) {
                        canLaunchShortcut = false
                        try {
                            appUtils.launchApp(activity.componentName, launcherApps.profiles[profileIndex])
                        } catch (e: Exception) {
                            logger.e("MainActivity", "Failed to launch right swipe app", e)
                            Toast.makeText(this@MainActivity, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        logger.w("MainActivity", "Right swipe gesture failed: no app configured")
                        Toast.makeText(this@MainActivity, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            super.onLongPress(e)
            trySettings()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (sharedPreferenceManager.isDoubleTapEnabled()) {
                when (sharedPreferenceManager.getDoubleTapAction()) {
                    "app" -> {
                        val (activity, profileIndex) = doubleTapApp
                        if (activity != null && profileIndex != null && profileIndex in launcherApps.profiles.indices) {
                            try {
                                appUtils.launchApp(activity.componentName, launcherApps.profiles[profileIndex])
                            } catch (e: Exception) {
                                logger.e("MainActivity", "Failed to launch double tap app", e)
                                Toast.makeText(this@MainActivity, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this@MainActivity, getString(R.string.launch_error), Toast.LENGTH_SHORT).show()
                        }
                    }

                    else -> {
                        if (gestureUtils.isAccessibilityServiceEnabled(
                                ScreenLockService::class.java
                            )
                        ) {
                            val intent = Intent(this@MainActivity, ScreenLockService::class.java)
                            intent.action = "LOCK_SCREEN"
                            startService(intent)
                        } else {
                            gestureUtils.promptEnableAccessibility()
                        }
                    }
                }
            }

            return super.onDoubleTap(e)

        }

    }

    /**
     * TextGestureListener extends GestureListener but disables long press.
     * Used for shortcut TextViews where long press should trigger app selection
     * rather than opening settings.
     */
    inner class TextGestureListener : GestureListener() {
        override fun onLongPress(e: MotionEvent) {

        }
    }

    private inner class DateGestureListener : GestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            when (dateSectionAt(e)) {
                DateSection.WEATHER -> openWeatherApp()
                DateSection.BATTERY -> openBatterySettings()
                null -> openDateApp()
            }
            return true
        }
    }

    private inner class NowPlayingGestureListener : GestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            nowPlayingManager?.openApp()
            return true
        }
    }

    override fun onContactClick(contactId: Int) {
        val contactUri: Uri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_URI,
            contactId.toString()
        )

        val intent = Intent(Intent.ACTION_VIEW, contactUri)
        startActivity(intent)
        returnAllowed = false
    }

    override fun onContactShortcut(contactId: Int, contactName: String, shortcutView: TextView, shortcutIndex: Int) {
        shortcutView.text = contactName
        uiUtils.setPausedStyle(shortcutView, false)
        shortcutView.setOnClickListener {
            onContactClick(contactId)
        }
        sharedPreferenceManager.setShortcut(
            shortcutIndex,
            shortcutView.text,
            contactName,
            contactId,
            true
        )
        shortcutView.setCompoundDrawablesWithIntrinsicBounds(ResourcesCompat.getDrawable(resources, R.drawable.ic_empty, null), null, null, null)
        uiUtils.setDrawables(shortcutView, sharedPreferenceManager.getShortcutAlignment())
        backToHome()
    }
}
