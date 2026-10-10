package com.souleven.nothingos.hooks

/**
 * Preference keys and item identifiers for the text selection toolbar tweaks.
 * Shared between the hook side (any app process) and the settings UI.
 */
object TextToolbarKeys {
    const val ENABLED = "tt_enabled"
    const val ORDER = "tt_order"                    // comma separated item keys
    const val HIDDEN = "tt_hidden"                  // comma separated item keys
    const val LABELS = "tt_labels"                  // JSON object: item key -> custom short label
    const val LABEL_MODE = "tt_label_mode"          // full | short
    const val LABEL_LEN = "tt_label_len"            // "1".."8"
    const val LABEL_UPPER = "tt_label_upper"
    const val LABEL_LINES = "tt_label_lines"        // "1" | "2"
    const val LABEL_SIZE = "tt_label_size"          // "auto" | "11".."16" (sp)
    const val DEBUG = "tt_debug"
    const val EXP_MENU_AUTOCOPY = "tt_exp_menu_autocopy" // Chrome / WebView auto-copy via the menu
    const val EXP_TELEGRAM = "tt_exp_telegram"           // Telegram (and forks) own toolbar
    const val NO_OVERFLOW = "tt_no_overflow"
    const val POSITION = "tt_position"              // default | below | above_right | below_right
    const val AUTOCOPY = "tt_autocopy"
    const val AUTOCOPY_FEEDBACK = "tt_autocopy_feedback" // none | top | system

    /** ClipData label used for auto-copied text, lets SystemUI recognise it. */
    const val AUTOCOPY_CLIP_LABEL = "NothingTweaks:autocopy"

    const val LABEL_MODE_FULL = "full"
    const val LABEL_MODE_SHORT = "short"

    const val POSITION_DEFAULT = "default"
    const val POSITION_BELOW = "below"
    const val POSITION_ABOVE_RIGHT = "above_right"
    const val POSITION_BELOW_RIGHT = "below_right"

    const val FEEDBACK_NONE = "none"
    const val FEEDBACK_TOP = "top"
    const val FEEDBACK_SYSTEM = "system"

    // Built-in items (system order)
    const val ITEM_ASSIST = "assist"
    const val ITEM_CUT = "cut"
    const val ITEM_COPY = "copy"
    const val ITEM_PASTE = "paste"
    const val ITEM_SHARE = "share"
    const val ITEM_SELECT_ALL = "select_all"
    const val ITEM_REPLACE = "replace"
    const val ITEM_AUTOFILL = "autofill"
    const val ITEM_PASTE_PLAIN = "paste_plain"
    const val ITEM_ASSIST_SECONDARY = "assist_secondary"

    val BUILTIN_ITEMS = listOf(
        ITEM_ASSIST, ITEM_CUT, ITEM_COPY, ITEM_PASTE, ITEM_SHARE, ITEM_SELECT_ALL,
        ITEM_REPLACE, ITEM_AUTOFILL, ITEM_PASTE_PLAIN, ITEM_ASSIST_SECONDARY
    )

    /** Prefix for ACTION_PROCESS_TEXT items: "pt:<package>/<fully.qualified.Activity>". */
    const val PROCESS_TEXT_PREFIX = "pt:"

    fun processTextKey(pkg: String, cls: String) = "$PROCESS_TEXT_PREFIX$pkg/$cls"

    fun parseList(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun joinList(items: Collection<String>): String = items.joinToString(",")
}
