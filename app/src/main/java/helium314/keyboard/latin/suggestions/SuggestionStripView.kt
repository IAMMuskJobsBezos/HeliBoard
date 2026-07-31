/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.addPinnedKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getPinnedToolbarKeys
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.removeFirst
import helium314.keyboard.latin.utils.removePinnedKey
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import androidx.core.view.isGone
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.onLongClickToolbarKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("InflateParams")
class SuggestionStripView(context: Context, attrs: AttributeSet?, defStyle: Int) :
    RelativeLayout(context, attrs, defStyle), View.OnClickListener, OnLongClickListener, OnSharedPreferenceChangeListener {

    /** Construct a [SuggestionStripView] for showing suggestions to be picked by the user. */
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.suggestionStripViewStyle)

    interface Listener {
        fun pickSuggestionManually(word: SuggestedWordInfo?)
        fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean)
        fun removeSuggestion(word: String?)
        fun removeExternalSuggestions()
        fun onSwipeDownOnToolbar()
    }

    private val moreSuggestionsContainer: View
    private val wordViews = ArrayList<TextView>()
    private val debugInfoViews = ArrayList<TextView>()
    private val dividerViews = ArrayList<View>()

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.suggestions_strip, this)
        moreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null)

        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        repeat(SuggestedWords.MAX_SUGGESTIONS) {
            val word = TextView(context, null, R.attr.suggestionWordStyle)
            word.contentDescription = resources.getString(R.string.spoken_empty_suggestion)
            word.setOnClickListener(this)
            word.setOnLongClickListener(this)
            colors.setBackground(word, ColorType.STRIP_BACKGROUND)
            wordViews.add(word)
            val divider = inflater.inflate(R.layout.suggestion_divider, null)
            dividerViews.add(divider)
            val info = TextView(context, null, R.attr.suggestionWordStyle)
            info.setTextColor(colors.get(ColorType.KEY_TEXT))
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DEBUG_INFO_TEXT_SIZE_IN_DIP)
            debugInfoViews.add(info)
        }

        DEBUG_SUGGESTIONS = context.prefs().getBoolean(DebugSettings.PREF_SHOW_SUGGESTION_INFOS, Defaults.PREF_SHOW_SUGGESTION_INFOS)
    }

    // toolbar views, drawables and setup
    private val toolbar: ViewGroup = findViewById(R.id.toolbar)
    private val toolbarContainer: View = findViewById(R.id.toolbar_container)
    private val suggestionsStripWrapper: ViewGroup = findViewById(R.id.suggestions_strip_wrapper)
    private val pinnedKeys: ViewGroup = findViewById(R.id.pinned_keys)
    private val suggestionsStrip: ViewGroup = findViewById(R.id.suggestions_strip)
    private val toolbarExpandKey = findViewById<ImageButton>(R.id.suggestions_strip_toolbar_key)
    private val incognitoIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.INCOGNITO.name, context)
    private val toolbarArrowIcon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_TOOLBAR_KEY, context)
    private val defaultToolbarBackground: Drawable = toolbarExpandKey.background
    private val enabledToolKeyBackground = GradientDrawable()
    private var direction = 1 // 1 if LTR, -1 if RTL
    private var voicePillView: View? = null
    // Must be initialized before the init block below - it calls updateKeys() ->
    // updateVoiceKey() -> updateVoicePillStretch(), which reads this.
    private var suggestedWords = SuggestedWords.getEmptyInstance()

    private val toolbarKeyLayoutParams = LinearLayout.LayoutParams(
        resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width),
        LinearLayout.LayoutParams.MATCH_PARENT
    )

    init {
        val colors = Settings.getValues().mColors

        // expand key
        // weird way of setting size (default is config_suggestions_strip_edge_key_width)
        // but better not change it or people will complain
        val toolbarHeight = min(toolbarExpandKey.layoutParams.height, resources.getDimension(R.dimen.config_suggestions_strip_height).toInt())
        toolbarExpandKey.layoutParams.height = toolbarHeight
        toolbarExpandKey.layoutParams.width = toolbarHeight // we want it square
        colors.setBackground(toolbarExpandKey, ColorType.STRIP_BACKGROUND) // necessary because background is re-used for defaultToolbarBackground
        colors.setColor(toolbarExpandKey, ColorType.TOOL_BAR_EXPAND_KEY)
        colors.setColor(toolbarExpandKey.background, ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND)

        // background indicator for pinned keys
        val color = colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND) or -0x1000000 // ignore alpha (in Java this is more readable 0xFF000000)
        enabledToolKeyBackground.colors = intArrayOf(color, Color.TRANSPARENT)
        enabledToolKeyBackground.gradientType = GradientDrawable.RADIAL_GRADIENT
        enabledToolKeyBackground.gradientRadius = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) / 2.1f

        val mToolbarMode = if (isGone) ToolbarMode.HIDDEN else Settings.getValues().mToolbarMode
        if (mToolbarMode == ToolbarMode.TOOLBAR_KEYS) {
            setToolbarVisibility(true)
        }

        // The voice pill's stretch/collapse (see updateVoicePillStretch) changes pinnedKeys' and
        // suggestionsStrip's widths via layoutParams, which android:animateLayoutChanges="true"
        // (see suggestions_strip.xml) does NOT animate on its own - that XML attribute only wires
        // up APPEARING/DISAPPEARING, not CHANGING, which must be enabled in code. Without this,
        // the pill's right edge stays anchored (it's flush against the row's end) but the resize
        // itself snaps instead of sliding.
        suggestionsStripWrapper.layoutTransition?.apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(LayoutTransition.CHANGING, VOICE_PILL_COLLAPSE_MS)
            setInterpolator(LayoutTransition.CHANGING, DecelerateInterpolator())
        }

        // toolbar keys setup (no need to hide them any more when locked, because then suggestion strip is gone anyway
        for (key in getEnabledToolbarKeys(context.prefs())) {
            val button = createToolbarKey(context, key)
            button.layoutParams = toolbarKeyLayoutParams
            setupKey(button, colors)
            toolbar.addView(button)
        }
        for (pinnedKey in getPinnedToolbarKeys(context.prefs())) {
            if (pinnedKey == ToolbarKey.VOICE) {
                val pill = createVoicePillKey(context, colors)
                voicePillView = pill
                pinnedKeys.addView(pill)
            } else {
                val button = createToolbarKey(context, pinnedKey)
                button.layoutParams = toolbarKeyLayoutParams
                setupKey(button, colors)
                pinnedKeys.addView(button)
            }
            val pinnedKeyInToolbar = toolbar.findViewWithTag<View>(pinnedKey)
            if (pinnedKeyInToolbar != null && Settings.getValues().mQuickPinToolbarKeys)
                pinnedKeyInToolbar.background = enabledToolKeyBackground
        }
        toolbarContainer.doOnNextLayout {
            // set min with of the toolbar so the weight of the toolbar keys actually does something
            // todo: results in requestLayout() improperly called by android.widget.LinearLayout during layout: running second layout pass
            toolbar.minimumWidth = toolbarContainer.width
        }

        updateKeys()
    }

    private lateinit var listener: Listener
    private var startIndexOfMoreSuggestions = 0
    private var isExternalSuggestionVisible = false // Required to disable the more suggestions if other suggestions are visible
    private val layoutHelper = SuggestionStripLayoutHelper(context, attrs, defStyle, wordViews, dividerViews, debugInfoViews)
    private val moreSuggestionsView = moreSuggestionsContainer.findViewById<MoreSuggestionsView>(R.id.more_suggestions_view).apply {
        val slidingListener = object : SimpleOnGestureListener() {
            override fun onScroll(down: MotionEvent?, me: MotionEvent, deltaX: Float, deltaY: Float): Boolean {
                if (down == null) return false
                val dy = me.y - down.y
                val dx = me.x - down.x

                if (Settings.getValues().mToolbarSwipeDownToHide && dy > 50.dpToPx(resources) && abs(dy) > abs(dx)) {
                    listener.onSwipeDownOnToolbar()
                    return true
                }

                return if (!isExternalSuggestionVisible && toolbarContainer.visibility != VISIBLE && deltaY > 0 && dy < (-10).dpToPx(resources)) showMoreSuggestions()
                else false
            }
        }
        gestureDetector = GestureDetector(context, slidingListener)
    }

    // public stuff

    val isShowingMoreSuggestionPanel get() = moreSuggestionsView.isShowingInParent

    /** A connection back to the input method. */
    fun setListener(newListener: Listener, inputView: View) {
        listener = newListener
        moreSuggestionsView.listener = newListener
        moreSuggestionsView.mainKeyboardView = inputView.findViewById(R.id.keyboard_view)
    }

    fun setRtl(isRtlLanguage: Boolean) {
        val newLayoutDirection: Int
        if (!Settings.getValues().mVarToolbarDirection)
            newLayoutDirection = LAYOUT_DIRECTION_LOCALE
        else {
            newLayoutDirection = if (isRtlLanguage) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
            direction = if (isRtlLanguage) -1 else 1
            toolbarExpandKey.scaleX = (if (toolbarContainer.visibility != VISIBLE) 1f else -1f) * direction
        }
        layoutDirection = newLayoutDirection
        suggestionsStrip.layoutDirection = newLayoutDirection
    }

    fun setToolbarVisibility(toolbarVisible: Boolean) {
        pinnedKeys.isVisible = !toolbarVisible
        suggestionsStrip.isVisible = !toolbarVisible
        toolbarContainer.isVisible = toolbarVisible

        if (DEBUG_SUGGESTIONS) {
            for (view in debugInfoViews) {
                view.visibility = suggestionsStrip.visibility
            }
        }

        toolbarExpandKey.scaleX = (if (toolbarVisible) -1f else 1f) * direction
    }

    fun setSuggestions(suggestions: SuggestedWords, isRtlLanguage: Boolean) {
        clear()
        setRtl(isRtlLanguage)
        suggestedWords = suggestions
        startIndexOfMoreSuggestions = layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
            context, suggestedWords, suggestionsStrip, this
        )
        isExternalSuggestionVisible = false
        updateKeys()
    }

    fun setExternalSuggestionView(view: View?, addCloseButton: Boolean) {
        clear()
        isExternalSuggestionVisible = true

        if (addCloseButton) {
            val wrapper = LinearLayout(context)
            suggestionsStrip.doOnNextLayout {
                wrapper.layoutParams = LinearLayout.LayoutParams(suggestionsStrip.width - 30.dpToPx(resources), LayoutParams.MATCH_PARENT)
            }
            wrapper.addView(view)
            suggestionsStrip.addView(wrapper)

            val closeButton = createToolbarKey(context, ToolbarKey.CLOSE_HISTORY)
            closeButton.layoutParams = toolbarKeyLayoutParams
            setupKey(closeButton, Settings.getValues().mColors)
            closeButton.setOnClickListener {
                listener.removeExternalSuggestions()
            }
            suggestionsStrip.addView(closeButton)
        } else {
            suggestionsStrip.addView(view)
        }

        if (Settings.getValues().mAutoHideToolbar) setToolbarVisibility(false)
    }

    fun setMoreSuggestionsHeight(remainingHeight: Int) {
        layoutHelper.setMoreSuggestionsHeight(remainingHeight)
    }

    fun dismissMoreSuggestionsPanel() {
        moreSuggestionsView.dismissPopupKeysPanel()
    }

    // overrides: necessarily public, but not used from outside

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(pinnedKeys, key)
        setToolbarButtonsActivatedStateOnPrefChange(toolbar, key)
        if (key == Settings.PREF_ALWAYS_INCOGNITO_MODE)
            GlobalScope.launch { delay(10); withContext(Dispatchers.Main) { updateKeys() } }
    }

    override fun onVisibilityChanged(view: View, visibility: Int) {
        super.onVisibilityChanged(view, visibility)
        // workaround for a bug with inline suggestions views that just keep showing up otherwise, https://github.com/HeliBorg/HeliBoard/pull/386
        if (view === this)
            suggestionsStrip.visibility = visibility
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissMoreSuggestionsPanel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Called by the framework when the size is known. Show the important notice if applicable.
        // This may be overridden by showing suggestions later, if applicable.
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        // Don't populate accessibility event with suggested words and voice key.
        return true
    }

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        // Detecting sliding up finger to show MoreSuggestionsView.
        return moreSuggestionsView.shouldInterceptTouchEvent(motionEvent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(motionEvent: MotionEvent): Boolean {
        moreSuggestionsView.touchEvent(motionEvent)
        return true
    }

    override fun onClick(view: View) {
        val tag = view.tag
        if (tag is ToolbarKey) {
            onClickToolbarKey(view) { listener.onCodeInput(it, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false) }
            return
        }
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
        if (view === toolbarExpandKey) {
            setToolbarVisibility(toolbarContainer.visibility != VISIBLE)
        }

        // tag for word views is set in SuggestionStripLayoutHelper (setupWordViewsTextAndColor, layoutPunctuationSuggestions)
        if (tag is Int) {
            if (tag >= suggestedWords.size()) {
                return
            }
            val wordInfo = suggestedWords.getInfo(tag)
            listener.pickSuggestionManually(wordInfo)
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (view.tag is ToolbarKey) {
            onLongClickToolbarKey(view)
            return true
        }
        AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
        return if (view is TextView && wordViews.contains(view)) {
            onLongClickSuggestion(view)
        } else {
            showMoreSuggestions()
        }
    }

    // actually private stuff

    private fun onLongClickToolbarKey(view: View) {
        val tag = view.tag as? ToolbarKey ?: return
        if (!Settings.getValues().mQuickPinToolbarKeys || view.parent === pinnedKeys) {
            onLongClickToolbarKey(view) { code, isRepeat -> listener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, isRepeat) }
        } else if (view.parent === toolbar) {
            AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
            val pinnedKeyView = pinnedKeys.findViewWithTag<View>(tag)
            if (pinnedKeyView == null) {
                addKeyToPinnedKeys(tag)
                toolbar.findViewWithTag<View>(tag).background = enabledToolKeyBackground
                addPinnedKey(context.prefs(), tag)
            } else {
                removePinnedKey(context.prefs(), tag)
                toolbar.findViewWithTag<View>(tag).background = defaultToolbarBackground.constantState?.newDrawable(resources)
                pinnedKeys.removeView(pinnedKeyView)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility") // no need for View#performClick, we only return false mostly anyway
    private fun onLongClickSuggestion(wordView: TextView): Boolean {
        var showIcon = true
        if (wordView.tag is Int) {
            val index = wordView.tag as Int
            val type = suggestedWords.getInfo(index).mSourceDict
            if (type == Dictionary.DICTIONARY_USER_TYPED || type == Dictionary.DICTIONARY_HARDCODED)
                showIcon = false
        }
        if (showIcon) {
            val icon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_BIN, context)!!
            Settings.getValues().mColors.setColor(icon, ColorType.REMOVE_SUGGESTION_ICON)
            val w = icon.intrinsicWidth
            val h = icon.intrinsicHeight
            wordView.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            wordView.ellipsize = TextUtils.TruncateAt.END
            val downOk = AtomicBoolean(false)
            wordView.setOnTouchListener { _, motionEvent ->
                if (motionEvent.action == MotionEvent.ACTION_UP && downOk.get()) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        removeSuggestion(wordView)
                        wordView.cancelLongPress()
                        wordView.isPressed = false
                        return@setOnTouchListener true
                    }
                } else if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        downOk.set(true)
                    }
                }
                false
            }
        }
        if (DebugFlags.DEBUG_ENABLED && (isShowingMoreSuggestionPanel || !showMoreSuggestions())) {
            showSourceDict(wordView)
            return true
        }
        return showMoreSuggestions()
    }

    private fun showMoreSuggestions(): Boolean {
        if (suggestedWords.size() <= startIndexOfMoreSuggestions) {
            return false
        }
        if (!moreSuggestionsView.show(
                suggestedWords, startIndexOfMoreSuggestions, moreSuggestionsContainer, layoutHelper, this
        ))
            return false
        for (i in 0..<startIndexOfMoreSuggestions) {
            wordViews[i].isPressed = false
        }
        return true
    }

    private fun showSourceDict(wordView: TextView) {
        val word = wordView.text.toString()
        val index = wordView.tag as? Int ?: return
        if (index >= suggestedWords.size()) return
        val info = suggestedWords.getInfo(index)
        if (info.word != word) return

        val text = info.mSourceDict.mDictType + ":" + info.mSourceDict.mLocale
        if (isShowingMoreSuggestionPanel) {
            moreSuggestionsView.dismissPopupKeysPanel()
        }
        KeyboardSwitcher.getInstance().showToast(text, true)
    }

    private fun removeSuggestion(wordView: TextView) {
        val word = wordView.text.toString()
        listener.removeSuggestion(word)
        moreSuggestionsView.dismissPopupKeysPanel()
        // show suggestions, but without the removed word
        val suggestedWordInfos = ArrayList<SuggestedWordInfo>()
        for (i in 0..<suggestedWords.size()) {
            val info = suggestedWords.getInfo(i)
            if (info.word != word) suggestedWordInfos.add(info)
        }
        suggestedWords.mRawSuggestions?.removeFirst { it.word == word }

        val newSuggestedWords = SuggestedWords(
            suggestedWordInfos, suggestedWords.mRawSuggestions, suggestedWords.typedWordInfo, suggestedWords.mTypedWordValid,
            suggestedWords.mWillAutoCorrect, suggestedWords.mIsObsoleteSuggestions, suggestedWords.mInputStyle, suggestedWords.mSequenceNumber
        )
        setSuggestions(newSuggestedWords, direction != 1)
        suggestionsStrip.isVisible = true

        // Show the toolbar if no suggestions are left and the "Auto show toolbar" setting is enabled
        if (this.suggestedWords.isEmpty && Settings.getValues().mAutoShowToolbar) {
            setToolbarVisibility(true)
        }
    }

    private fun clear() {
        suggestionsStrip.removeAllViews()
        if (DEBUG_SUGGESTIONS) removeAllDebugInfoViews()
        if (!toolbarContainer.isVisible)
            suggestionsStrip.isVisible = true
        dismissMoreSuggestionsPanel()
        for (word in wordViews) {
            word.setOnTouchListener(null)
        }
    }

    private fun removeAllDebugInfoViews() {
        for (debugInfoView in debugInfoViews) {
            val parent = debugInfoView.parent
            if (parent is ViewGroup) {
                parent.removeView(debugInfoView)
            }
        }
    }

    fun updateVoiceKey() {
        val show = Settings.getValues().mShowsVoiceInputKey
        toolbar.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
        // Elderly-phone build: the pinned voice key stays visible in the suggestion strip
        // regardless of whether a system voice-input IME is currently registered (there isn't
        // one yet - that's a separate, not-yet-built phase). It's a no-op tap until that engine
        // exists, but it must always be present in the row rather than flicker in and out.
        pinnedKeys.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = true
        updateVoicePillStretch()
    }

    /**
     * With no word suggestions to show, [suggestionsStrip] sits empty next to the pill, reading
     * as dead space. Stretching the pill to fill that space (rather than leaving it hugging the
     * edge) gives the elder user one obvious, thumb-sized target instead of a small pill floating
     * next to a blank strip - centered voice bar, no suggestion strip, since there's no text to
     * suggest against. Shrinks straight back to a small pill the moment real suggestions have
     * room to show.
     */
    private fun updateVoicePillStretch() {
        val pill = voicePillView ?: return
        val pinnedParams = pinnedKeys.layoutParams as? LinearLayout.LayoutParams ?: return
        val pillParams = pill.layoutParams as? LinearLayout.LayoutParams ?: return
        val suggestionsParams = suggestionsStrip.layoutParams as? LinearLayout.LayoutParams ?: return
        // Punctuation suggestions (shown on an empty field) aren't real word candidates - the
        // strip reads as visually empty either way, so treat both as "nothing to show". Never
        // stretch while an external suggestion view (e.g. clipboard history) is hosted in
        // suggestionsStrip - that content must stay visible regardless of suggestedWords.
        val stretch = (suggestedWords.isEmpty || suggestedWords.isPunctuationSuggestions) && !isExternalSuggestionVisible
        // suggestionsStrip normally shares layout_weight="1" with this row to claim the leftover
        // space itself (see suggestions_strip.xml); zero it out here so that space goes to the
        // pill instead of being split between an empty strip and the pill.
        suggestionsParams.weight = if (stretch) 0f else 1f
        pinnedParams.width = if (stretch) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
        pinnedParams.weight = if (stretch) 1f else 0f
        pillParams.width = if (stretch) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT
        suggestionsStrip.layoutParams = suggestionsParams
        pinnedKeys.layoutParams = pinnedParams
        pill.layoutParams = pillParams
        // Take the suggestion strip fully out (not just zero-width) so nothing of it remains
        // when there's only the voice bar to show. updateKeys() below reads suggestionsStrip's
        // PRE-stretch visibility for pinnedKeys (captured before this function runs), so toggling
        // this here can never also hide the pill - that coupling is what caused the pill row to
        // vanish entirely the last time this was attempted.
        suggestionsStrip.isVisible = !stretch
    }

    private fun updateKeys() {
        // Captured before updateVoiceKey() (-> updateVoicePillStretch()) can change
        // suggestionsStrip's visibility for the stretch/empty state - pinnedKeys must follow
        // suggestionsStrip's toolbar-toggle/global-visibility state only, never the stretch state,
        // or the voice pill inside pinnedKeys gets hidden along with the empty strip.
        val pinnedKeysVisibility = suggestionsStrip.visibility
        updateVoiceKey()
        val settingsValues = Settings.getValues()

        val toolbarIsExpandable = settingsValues.mToolbarMode == ToolbarMode.EXPANDABLE
        if (settingsValues.mIncognitoModeEnabled) {
            toolbarExpandKey.setImageDrawable(incognitoIcon)
            toolbarExpandKey.isVisible = true
        } else {
            toolbarExpandKey.setImageDrawable(toolbarArrowIcon)
            toolbarExpandKey.isVisible = toolbarIsExpandable
        }

        toolbarExpandKey.setOnClickListener(if (!toolbarIsExpandable) null else this)
        pinnedKeys.visibility = pinnedKeysVisibility
        isExternalSuggestionVisible = false
    }

    private fun addKeyToPinnedKeys(pinnedKey: ToolbarKey) {
        val original = toolbar.findViewWithTag<ImageButton>(pinnedKey) ?: return
        // copy the original key to a new ImageButton
        val copy = ImageButton(context, null, R.attr.suggestionWordStyle)
        copy.tag = pinnedKey
        copy.scaleType = original.scaleType
        copy.scaleX = original.scaleX
        copy.scaleY = original.scaleY
        copy.contentDescription = original.contentDescription
        copy.setImageDrawable(original.drawable)
        copy.layoutParams = original.layoutParams
        copy.isActivated = original.isActivated
        setupKey(copy, Settings.getValues().mColors)
        pinnedKeys.addView(copy)
    }

    private fun setupKey(view: ImageButton, colors: Colors) {
        view.setOnClickListener(this)
        view.setOnLongClickListener(this)
        (view.layoutParams as LinearLayout.LayoutParams).weight = 1f
        colors.setColor(view, ColorType.TOOL_BAR_KEY)
        colors.setBackground(view, ColorType.STRIP_BACKGROUND)
    }

    // Elderly-phone shipped default: the pinned voice key is styled as an outlined pill with a
    // mic icon and a "Voice" text label, matching the "Voice to text" / "Voice" buttons used
    // elsewhere in the app suite (Messages/Notes), instead of a bare icon glyph like the other
    // toolbar keys. It still dispatches through the normal ToolbarKey.VOICE click/long-click path
    // (tag-based, see onClick/onLongClick) - only the visual is different. Not wired to an actual
    // voice-input engine yet; that is a separate, not-yet-built phase.
    private fun createVoicePillKey(context: Context, colors: Colors): View {
        val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        val navy = "#0B1B41".toColorInt()
        val strokeWidthPx = 2.dpToPx(resources)
        val cornerRadiusPx = 999.dpToPx(resources).toFloat()
        // Elderly-phone shipped default: bigger touch target/icon to match the taller strip
        // (config_suggestions_strip_height) - dexterity accommodation (was 14/6/22dp). Padding
        // and gap pulled back in twice now (20/8dp, then 14/6dp) so the collapsed pill leaves the
        // suggestion row more room when both are showing side by side; icon size (the touch
        // target) is untouched.
        val horizontalPaddingPx = 9.dpToPx(resources)
        val iconTextGapPx = 0.dpToPx(resources)
        val iconSizePx = 32.dpToPx(resources)

        val pillBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(colors.get(ColorType.KEY_BACKGROUND))
            setStroke(strokeWidthPx, accent)
        }

        val icon = ImageView(context).apply {
            // Lighter faux-bold than the rest of the keyboard's icons: the standard 0.8dp
            // strength (see KeyboardIconsSet) read as too heavy/blurry at this icon's larger
            // 32dp size next to the "Voice" label - a touch of weight, less than the label's,
            // just enough that the glyph doesn't look thin beside it.
            setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.VOICE.name, context, boldOffsetDp = 0.35f))
            setColorFilter(navy)
            layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
        }
        val label = TextView(context, null, R.attr.suggestionWordStyle).apply {
            text = "Voice" // matches the "Voice to text" / "Voice" pill used elsewhere in the app suite
            setTextColor(navy)
            setSingleLine(true)
            // Faux semi-bold via a slight paint stroke - darker than the plain regular weight
            // without the full heaviness of switching to a true bold typeface. Software layer
            // type is required: hardware-accelerated Canvas ignores Paint.strokeWidth on text.
            paint.strokeWidth = 0.6f * resources.displayMetrics.density
            paint.style = Paint.Style.FILL_AND_STROKE
            setLayerType(View.LAYER_TYPE_SOFTWARE, paint)
        }

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            tag = ToolbarKey.VOICE
            contentDescription = ToolbarKey.VOICE.name.lowercase().getStringResourceOrName("", context)
            background = pillBackground
            setPadding(horizontalPaddingPx, 0, horizontalPaddingPx, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                val vMargin = 6.dpToPx(resources)
                setMargins(4.dpToPx(resources), vMargin, 4.dpToPx(resources), vMargin)
            }
            addView(icon)
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = iconTextGapPx
            })
            setOnClickListener(this@SuggestionStripView)
            setOnLongClickListener(this@SuggestionStripView)
        }
        return pill
    }

    companion object {
        @JvmField
        var DEBUG_SUGGESTIONS = false
        private const val DEBUG_INFO_TEXT_SIZE_IN_DIP = 6.5f
        private const val VOICE_PILL_COLLAPSE_MS = 200L
        private val TAG = SuggestionStripView::class.java.simpleName
    }
}
