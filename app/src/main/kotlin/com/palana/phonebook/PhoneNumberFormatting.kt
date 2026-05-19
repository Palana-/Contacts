package com.palana.phonebook

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

fun phoneNumberGroups(phone: String): List<String> {
    val digits = normalizeMainlandMobileNumber(phone)
    if (digits.isEmpty()) return listOf(phone)
    if (digits.length <= 6) return listOf(digits)
    if (digits.length == 11 && digits.startsWith("1")) return listOf(digits.take(3), digits.drop(3).take(4), digits.drop(7))
    landlineGroups(digits)?.let { return it }
    if (digits.length == 8) return listOf(digits.take(4), digits.drop(4))
    return listOf(digits)
}

fun groupedPhoneNumber(phone: String): String = phoneNumberGroups(phone).joinToString(" ")

fun phoneDigitsOnly(value: String): String = value.filter { it.isDigit() }

fun normalizeMainlandMobileNumber(value: String): String {
    val digits = phoneDigitsOnly(value)
    return if (digits.length == 13 && digits.startsWith("86") && digits[2] == '1') {
        digits.drop(2)
    } else {
        digits
    }
}

private fun landlineGroups(digits: String): List<String>? {
    if (digits.length !in 10..12 || !digits.startsWith("0")) return null
    return when {
        digits.startsWith("010") || digits.startsWith("020") || digits.startsWith("021") || digits.startsWith("022") ||
            digits.startsWith("023") || digits.startsWith("024") || digits.startsWith("025") || digits.startsWith("027") ||
            digits.startsWith("028") || digits.startsWith("029") -> listOf(digits.take(3), digits.drop(3))
        digits.length >= 11 -> listOf(digits.take(4), digits.drop(4))
        else -> null
    }
}

class PhoneNumberVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val transformed = groupedPhoneNumber(source)
        val originalToTransformed = IntArray(source.length + 1)
        val transformedToOriginal = IntArray(transformed.length + 1)
        var originalIndex = 0

        transformed.forEachIndexed { transformedIndex, char ->
            transformedToOriginal[transformedIndex] = originalIndex
            if (char != ' ') {
                originalToTransformed[originalIndex] = transformedIndex
                originalIndex++
            }
        }
        transformedToOriginal[transformed.length] = source.length
        originalToTransformed[source.length] = transformed.length

        return TransformedText(
            AnnotatedString(transformed),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int {
                    return originalToTransformed[offset.coerceIn(0, source.length)]
                }

                override fun transformedToOriginal(offset: Int): Int {
                    return transformedToOriginal[offset.coerceIn(0, transformed.length)]
                }
            }
        )
    }
}
