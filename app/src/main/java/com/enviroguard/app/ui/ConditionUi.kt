package com.enviroguard.app.ui

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.enviroguard.app.R
import com.enviroguard.app.model.EnvironmentalCondition

object ConditionUi {
    @ColorRes
    fun accent(condition: EnvironmentalCondition) = when (condition) {
        EnvironmentalCondition.GOOD -> R.color.ehm_good
        EnvironmentalCondition.MODERATE -> R.color.ehm_moderate
        EnvironmentalCondition.POOR -> R.color.ehm_poor
        EnvironmentalCondition.CRITICAL -> R.color.ehm_critical
    }

    @ColorRes
    fun container(condition: EnvironmentalCondition) = when (condition) {
        EnvironmentalCondition.GOOD -> R.color.ehm_good_container
        EnvironmentalCondition.MODERATE -> R.color.ehm_moderate_container
        EnvironmentalCondition.POOR -> R.color.ehm_poor_container
        EnvironmentalCondition.CRITICAL -> R.color.ehm_critical_container
    }

    fun applyChip(view: TextView, condition: EnvironmentalCondition?) {
        if (condition == null) {
            view.text = view.context.getString(R.string.status_unavailable)
            view.setTextColor(ContextCompat.getColor(view.context, R.color.ehm_on_surface_variant))
            view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(view.context, R.color.ehm_surface_variant))
            return
        }
        view.text = condition.displayName
        view.setTextColor(ContextCompat.getColor(view.context, accent(condition)))
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(view.context, container(condition)))
    }
}
