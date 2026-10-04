package ru.gigapisar.insertion

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.accessibility.AccessibilityNodeInfo
import ru.gigapisar.R

class TextInserter(
    private val context: Context,
) {
    private val mainHandler =
        Handler(Looper.getMainLooper())

    /**
     * Puts [text] on the clipboard. A [transient] clip is only there to be pasted: it is marked
     * sensitive, so Android shows no preview of it and keyboards keep it out of their history.
     */
    fun putToClipboard(
        text: String,
        transient: Boolean = false,
    ) {
        val clipboard =
            context.getSystemService(
                Context.CLIPBOARD_SERVICE,
            ) as ClipboardManager

        val clip = ClipData.newPlainText(context.getString(R.string.clipboard_label), text)
        if (transient) {
            clip.description.extras =
                PersistableBundle().apply {
                    putBoolean(
                        if (Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.TIRAMISU
                        ) {
                            ClipDescription.EXTRA_IS_SENSITIVE
                        } else {
                            "android.content.extra.IS_SENSITIVE"
                        },
                        true,
                    )
                }
        }
        clipboard.setPrimaryClip(clip)
    }

    /** Where a dictation went in: the field and the offset of the text, so it can be swapped back. */
    class Insertion(
        val node: AccessibilityNodeInfo,
        val start: Int,
    )

    /**
     * Puts [text] into the focused field at the cursor. First written straight into the field,
     * without the clipboard: Android then shows no "pasted from clipboard" popup over the
     * keyboard and whatever the user copied stays in the clipboard. Fields that do not take
     * text that way (some rich editors, password fields) get the old paste through the clipboard.
     * Returns null when the text did not go in.
     */
    fun insertIntoFocusedField(
        fallbackNode: AccessibilityNodeInfo?,
        text: String,
        allowPaste: Boolean = true,
    ): Insertion? {
        val node = findFocusedNode() ?: fallbackNode ?: return null
        return try {
            if (!node.isEditable || !node.isEnabled) return null
            insertDirectly(node, text) ?: if (allowPaste) paste(node, text) else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Swaps [inserted] at [insertion] back to [original]. False when the field changed since
     * (the user typed, or left the app): then nothing is touched blindly.
     */
    fun replaceInserted(
        insertion: Insertion,
        inserted: String,
        original: String,
    ): Boolean =
        try {
            val node = insertion.node
            if (!node.refresh()) return false
            val current = fieldText(node)
            val at =
                if (current.startsWith(inserted, insertion.start)) {
                    insertion.start
                } else {
                    // The app may have shifted the text a little (e.g. trimmed a space); accept one clear match.
                    current.indexOf(inserted).takeIf { it >= 0 && it == current.lastIndexOf(inserted) } ?: return false
                }
            setText(node, current.substring(0, at) + original + current.substring(at + inserted.length), at + original.length)
        } catch (_: Throwable) {
            false
        }

    private fun insertDirectly(
        node: AccessibilityNodeInfo,
        text: String,
    ): Insertion? {
        // Only plain input fields: there the text we read is the whole text, so writing it back
        // loses nothing. Anything else (web editors, custom views) goes through paste.
        if (node.isPassword || node.className?.toString() != "android.widget.EditText") return null
        val current = fieldText(node)
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        // A focused plain field always knows its cursor. When it does not say, what we read
        // may not be its real text (a hint drawn by the app, say): leave it to paste.
        if (selStart !in 0..current.length) return null
        val start = selStart
        val end = if (selEnd in start..current.length) selEnd else start
        val updated = current.substring(0, start) + text + current.substring(end)
        if (!setText(node, updated, start + text.length)) return null
        // Some apps accept the action but ignore it: only count it when the field shows a change.
        // Browsers and Keep apply it a moment later or reshape the text (line breaks), so wait a
        // little, and once the field changed at all never paste on top: that doubled the text.
        repeat(VERIFY_ATTEMPTS) {
            node.refresh()
            val now = fieldText(node)
            if (now.startsWith(text, start) || now != current) return Insertion(node, start)
            Thread.sleep(VERIFY_STEP_MS)
        }
        return null
    }

    private fun paste(
        node: AccessibilityNodeInfo,
        text: String,
    ): Insertion? {
        val current = fieldText(node)
        val selStart = node.textSelectionStart
        val start = if (selStart in 0..current.length) selStart else current.length
        putToClipboard(text, transient = true)
        // Cleared either way: a failed paste must not leave the dictation on the clipboard.
        mainHandler.postDelayed(::clearClipboard, CLIPBOARD_CLEAR_DELAY_MS)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return null
        return Insertion(node, start)
    }

    /** The field's own text; empty while it only shows its hint ("Сообщение", "Поиск"). */
    private fun fieldText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString() ?: ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (node.isShowingHintText) return ""
            // Telegram and others report the grey hint ("Message") as the text of an empty
            // field without flagging it; taken for real text, it ended up before the dictation.
            val hint = node.hintText?.toString()
            if (!hint.isNullOrEmpty() && text == hint) return ""
        }
        return text
    }

    private fun setText(
        node: AccessibilityNodeInfo,
        text: String,
        cursor: Int,
    ): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val selection =
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        return true
    }

    private fun clearClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private companion object {
        const val CLIPBOARD_CLEAR_DELAY_MS = 250L
        const val VERIFY_ATTEMPTS = 6
        const val VERIFY_STEP_MS = 50L
    }

    private fun findFocusedNode(): AccessibilityNodeInfo? {
        val service = context as? android.accessibilityservice.AccessibilityService ?: return null
        return try {
            val focused =
                service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

            if (focused != null && focused.isEditable && focused.isEnabled) {
                focused
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
