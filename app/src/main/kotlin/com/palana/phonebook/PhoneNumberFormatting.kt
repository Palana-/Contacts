package com.palana.phonebook

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

fun phoneNumberGroups(phone: String): List<String> {
    if (phone.length <= 3) return listOf(phone)
    if (phone.length <= 7) return listOf(phone.take(3), phone.drop(3))
    return listOf(phone.take(3), phone.drop(3).take(4), phone.drop(7))
}

fun groupedPhoneNumber(phone: String): String = phoneNumberGroups(phone).joinToString(" ")

fun phoneDigitsOnly(value: String): String = value.filter { it.isDigit() }

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
