package io.github.chenfish666.podspanel.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.chenfish666.podspanel.R
import io.github.chenfish666.podspanel.core.PodsStatus

/** Fills the row of battery cells shared by the popup and the main screen. */
object BatteryViews {

    private const val LOW_PERCENT = 20

    /** @param row a view containing the cells with ids cellLeft, cellRight and cellCase */
    fun bind(row: View, status: PodsStatus) {
        val left = row.findViewById<View>(R.id.cellLeft)
        val right = row.findViewById<View>(R.id.cellRight)
        val case = row.findViewById<View>(R.id.cellCase)

        if (status.model.dual) {
            cell(left, R.drawable.ic_pod_left, R.string.cell_left, status.left, status.leftCharging, status.leftInEar)
            cell(right, R.drawable.ic_pod_right, R.string.cell_right, status.right, status.rightCharging, status.rightInEar)
            cell(case, R.drawable.ic_case, R.string.cell_case, status.case, status.caseCharging, false)
            right.visibility = View.VISIBLE
            case.visibility = View.VISIBLE
        } else {
            cell(left, R.drawable.ic_pod_left, R.string.cell_headset, status.left, status.leftCharging, status.leftInEar)
            right.visibility = View.GONE
            case.visibility = View.GONE
        }
    }

    private fun cell(cell: View, icon: Int, label: Int, percent: Int?, charging: Boolean, inEar: Boolean) {
        val context = cell.context
        cell.findViewById<ImageView>(R.id.cellIcon).apply {
            setImageResource(icon)
            alpha = if (percent == null) 0.35f else 1f
        }
        cell.findViewById<TextView>(R.id.cellLabel).text =
            context.getString(label) + if (inEar) " · 配戴中" else ""
        cell.findViewById<TextView>(R.id.cellPercent).text = when {
            percent == null -> "—"
            charging -> "$percent% ⚡"
            else -> "$percent%"
        }
        cell.findViewById<ProgressBar>(R.id.cellBar).apply {
            progress = percent ?: 0
            val low = percent != null && percent <= LOW_PERCENT && !charging
            progressTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, if (low) R.color.battery_low else R.color.battery_ok)
            )
        }
        cell.contentDescription = context.getString(label) + " " + (percent?.let { "$it%" } ?: "未知") +
            if (charging) " 充電中" else ""
    }

    /** One-line summary for notifications. */
    fun summary(status: PodsStatus): String {
        fun part(label: String, percent: Int?, charging: Boolean) =
            percent?.let { "$label $it%" + if (charging) "⚡" else "" }
        val parts = if (status.model.dual) {
            listOfNotNull(
                part("左", status.left, status.leftCharging),
                part("右", status.right, status.rightCharging),
                part("盒", status.case, status.caseCharging),
            )
        } else {
            listOfNotNull(part("電量", status.left, status.leftCharging))
        }
        return if (parts.isEmpty()) "電量未知" else parts.joinToString(" · ")
    }
}
