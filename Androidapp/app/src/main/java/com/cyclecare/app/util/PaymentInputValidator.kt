package com.cyclecare.app.util

import java.time.YearMonth

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errorMessage: String) : ValidationResult()

    val isValid: Boolean get() = this is Valid
    val message: String? get() = (this as? Invalid)?.errorMessage
}

object PaymentInputValidator {

    /**
     * Strictly validates Kenyan phone numbers (Safaricom, Airtel, Telkom)
     * Valid formats:
     * - 07XXXXXXXX or 01XXXXXXXX (10 digits)
     * - +2547XXXXXXXX or +2541XXXXXXXX (13 chars)
     * - 2547XXXXXXXX or 2541XXXXXXXX (12 digits)
     */
    fun validateKenyanPhoneNumber(raw: String): ValidationResult {
        val trimmed = raw.trim().replace("\\s+".toRegex(), "")
        if (trimmed.isBlank()) {
            return ValidationResult.Invalid("Phone number is required.")
        }

        val digits = if (trimmed.startsWith("+")) trimmed.substring(1) else trimmed
        if (!digits.all { it.isDigit() }) {
            return ValidationResult.Invalid("Phone number must contain digits only.")
        }

        val normalized = when {
            digits.startsWith("0") && digits.length == 10 -> "254" + digits.substring(1)
            digits.startsWith("254") && digits.length == 12 -> digits
            digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "254$digits"
            else -> null
        }

        if (normalized == null || normalized.length != 12) {
            return ValidationResult.Invalid("Please enter a valid 10-digit number (e.g. 0712345678 or 0112345678).")
        }

        // Validate carrier prefix (7XX or 1XX)
        val prefix = normalized.substring(3, 5)
        val validPrefixDigit = normalized[3]
        if (validPrefixDigit != '7' && validPrefixDigit != '1') {
            return ValidationResult.Invalid("Kenyan mobile numbers must start with 07XX or 01XX.")
        }

        return ValidationResult.Valid
    }

    /**
     * Sanitizes a phone number to standard 2547XXXXXXXX / 2541XXXXXXXX format.
     */
    fun sanitizeKenyanPhoneNumber(raw: String): String {
        val digits = raw.trim().replace("\\s+".toRegex(), "").filter { it.isDigit() }
        return when {
            digits.startsWith("0") && digits.length == 10 -> "254" + digits.substring(1)
            digits.startsWith("254") && digits.length == 12 -> digits
            digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "254$digits"
            else -> digits
        }
    }

    /**
     * Validates card number using the Luhn Algorithm (Mod 10 Checksum)
     */
    fun validateCardNumber(raw: String): ValidationResult {
        val digits = raw.trim().replace("[\\s-]+".toRegex(), "")
        if (digits.isBlank()) {
            return ValidationResult.Invalid("Card number is required.")
        }
        if (!digits.all { it.isDigit() }) {
            return ValidationResult.Invalid("Card number must contain digits only.")
        }
        if (digits.length < 13 || digits.length > 19) {
            return ValidationResult.Invalid("Card number must be between 13 and 19 digits.")
        }

        // Luhn checksum algorithm
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i].digitToInt()
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }

        if (sum % 10 != 0) {
            return ValidationResult.Invalid("Invalid card number. Checksum verification failed.")
        }

        return ValidationResult.Valid
    }

    /**
     * Validates card expiry in MM/YY format against current month and year
     */
    fun validateCardExpiry(raw: String): ValidationResult {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return ValidationResult.Invalid("Expiry date is required.")
        }

        val parts = trimmed.split("/")
        if (parts.size != 2) {
            return ValidationResult.Invalid("Use MM/YY format (e.g. 12/28).")
        }

        val month = parts[0].toIntOrNull()
        val yearPart = parts[1].toIntOrNull()

        if (month == null || month !in 1..12) {
            return ValidationResult.Invalid("Month must be between 01 and 12.")
        }

        if (yearPart == null || parts[1].length != 2) {
            return ValidationResult.Invalid("Year must be 2 digits (e.g. 28).")
        }

        val fullYear = 2000 + yearPart
        try {
            val expiry = YearMonth.of(fullYear, month)
            val current = YearMonth.now()
            if (expiry.isBefore(current)) {
                return ValidationResult.Invalid("This card has expired.")
            }
        } catch (_: Exception) {
            return ValidationResult.Invalid("Invalid expiration date.")
        }

        return ValidationResult.Valid
    }

    /**
     * Validates CVV (3 or 4 digits)
     */
    fun validateCardCvv(raw: String): ValidationResult {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return ValidationResult.Invalid("CVV is required.")
        }
        if (!trimmed.all { it.isDigit() }) {
            return ValidationResult.Invalid("CVV must be numeric.")
        }
        if (trimmed.length !in 3..4) {
            return ValidationResult.Invalid("CVV must be 3 or 4 digits.")
        }
        return ValidationResult.Valid
    }
}
