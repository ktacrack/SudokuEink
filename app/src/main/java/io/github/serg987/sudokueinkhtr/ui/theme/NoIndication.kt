package io.github.serg987.sudokueinkhtr.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/**
 * No-op [IndicationNodeFactory] — installed app-wide (see [SudokuEinkTheme]) so
 * `clickable`/`combinedClickable`/`selectable`/`toggleable` never draw the default
 * light-gray ripple. On e-ink the ripple fill sits below the panel's refresh threshold and
 * lingers for ~1s as a visible ghost instead of animating away like on LCD — see AGENTS.md
 * "E-ink UI Guidelines" for the broader no-animation policy this follows.
 */
object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return object : Modifier.Node() {}
    }

    // IndicationNodeFactory redeclares these abstractly (it's typically implemented by
    // data objects/classes) so Compose can tell two factories apart without recreating
    // nodes. A plain `object` is already a stable singleton, so identity equality is
    // exactly right here.
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}
