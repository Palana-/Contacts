package com.palana.phonebook

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun TextStyle.bold(size: Int? = null): TextStyle {
    return copy(
        fontWeight = FontWeight.Bold,
        fontSize = size?.sp ?: fontSize
    )
}

val PhoneBookTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.bold(),
        displayMedium = base.displayMedium.bold(),
        displaySmall = base.displaySmall.bold(),
        headlineLarge = base.headlineLarge.bold(34),
        headlineMedium = base.headlineMedium.bold(30),
        headlineSmall = base.headlineSmall.bold(26),
        titleLarge = base.titleLarge.bold(25),
        titleMedium = base.titleMedium.bold(22),
        titleSmall = base.titleSmall.bold(20),
        bodyLarge = base.bodyLarge.bold(22),
        bodyMedium = base.bodyMedium.bold(20),
        bodySmall = base.bodySmall.bold(18),
        labelLarge = base.labelLarge.bold(20),
        labelMedium = base.labelMedium.bold(18),
        labelSmall = base.labelSmall.bold(16)
    )
}
