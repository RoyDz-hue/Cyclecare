package com.cyclecare.app.util


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

}
