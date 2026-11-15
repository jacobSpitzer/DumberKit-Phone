package org.fossify.phone.helpers

import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.KeypadHelper
import org.fossify.commons.models.contacts.Contact

/**
 * Pure physical-keypad search. Dialpad's same Commons keypad mapping is reused so
 * 5646 finds a name like "John"; numeric matches include unsaved-looking number fields.
 */
object KeypadContactSearch {
    fun matches(contact: Contact, query: String): Boolean {
        if (query.isEmpty()) return true
        val digits = query.filter(Char::isDigit)
        if (digits.isEmpty()) return false
        if (contact.phoneNumbers.any { number ->
                number.value.filter(Char::isDigit).contains(digits) ||
                    number.normalizedNumber.filter(Char::isDigit).contains(digits)
            }) return true
        return listOf(contact.getNameToDisplay(), contact.nickname)
            .filter { it.isNotBlank() }
            .any { value ->
                KeypadHelper.convertKeypadLettersToDigits(value.normalizeString())
                    .filter(Char::isDigit).contains(digits)
            }
    }

    fun startsWith(contact: Contact, query: String): Boolean {
        val digits = query.filter(Char::isDigit)
        if (digits.isEmpty()) return true
        return KeypadHelper.convertKeypadLettersToDigits(contact.getNameToDisplay().normalizeString())
            .filter(Char::isDigit).startsWith(digits)
    }
}
