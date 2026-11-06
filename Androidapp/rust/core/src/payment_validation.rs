//! Port of util/PaymentInputValidator.kt (main@31b88f5), verbatim validation
//! order and error strings. Clock seam: validateCardExpiry takes the current
//! year-month from the caller (the source reads YearMonth.now()).
//!
//! Regex notes: the source's "\\s+" and "[\\s-]+" are Kotlin default-flags
//! regexes - \s is ASCII-only [ \t\n\x0B\f\r]. trim() is Kotlin's full
//! isWhitespace set (see kotlin_text). Char.isDigit is BMP Nd (kotlin_text).

use crate::kotlin_text::{kotlin_is_digit, kotlin_regex_ascii_space, kotlin_to_int_or_null, kotlin_trim};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ValidationResult {
    Valid,
    Invalid(&'static str),
}

impl ValidationResult {
    pub fn is_valid(&self) -> bool {
        matches!(self, ValidationResult::Valid)
    }
    pub fn message(&self) -> Option<&'static str> {
        match self {
            ValidationResult::Invalid(m) => Some(m),
            _ => None,
        }
    }
}

fn strip_ascii_spaces(s: &str) -> String {
    s.chars().filter(|c| !kotlin_regex_ascii_space(*c)).collect()
}

fn strip_spaces_and_hyphens(s: &str) -> String {
    s.chars().filter(|c| !kotlin_regex_ascii_space(*c) && *c != '-').collect()
}

/// Strictly validates Kenyan phone numbers (Safaricom, Airtel, Telkom).
pub fn validate_kenyan_phone_number(raw: &str) -> ValidationResult {
    let trimmed = strip_ascii_spaces(kotlin_trim(raw));
    if trimmed.is_empty() {
        return ValidationResult::Invalid("Phone number is required.");
    }
    let digits: &str = trimmed.strip_prefix('+').unwrap_or(&trimmed);
    if !digits.chars().all(kotlin_is_digit) {
        return ValidationResult::Invalid("Phone number must contain digits only.");
    }
    let normalized: Option<String> = if digits.starts_with('0') && digits.chars().count() == 10 {
        Some(format!("254{}", &digits[1..]))
    } else if digits.starts_with("254") && digits.chars().count() == 12 {
        Some(digits.to_string())
    } else if digits.chars().count() == 9 && (digits.starts_with('7') || digits.starts_with('1')) {
        Some(format!("254{digits}"))
    } else {
        None
    };
    let normalized = match normalized {
        Some(n) if n.chars().count() == 12 => n,
        _ => {
            return ValidationResult::Invalid(
                "Please enter a valid 10-digit number (e.g. 0712345678 or 0112345678).",
            )
        }
    };
    // Carrier prefix: 4th digit of the normalized 254XXXXXXXXX form.
    let valid_prefix_digit = normalized.chars().nth(3).unwrap();
    if valid_prefix_digit != '7' && valid_prefix_digit != '1' {
        return ValidationResult::Invalid("Kenyan mobile numbers must start with 07XX or 01XX.");
    }
    ValidationResult::Valid
}

/// Sanitizes to standard 2547XXXXXXXX / 2541XXXXXXXX form; unmatched input is
/// returned digits-only, like the source.
pub fn sanitize_kenyan_phone_number(raw: &str) -> String {
    let digits: String = strip_ascii_spaces(kotlin_trim(raw))
        .chars()
        .filter(|c| kotlin_is_digit(*c))
        .collect();
    let len = digits.chars().count();
    if digits.starts_with('0') && len == 10 {
        format!("254{}", &digits[1..])
    } else if digits.starts_with("254") && len == 12 {
        digits
    } else if len == 9 && (digits.starts_with('7') || digits.starts_with('1')) {
        format!("254{digits}")
    } else {
        digits
    }
}

/// Luhn (Mod 10) validation, 13-19 digits, verbatim loop order.
pub fn validate_card_number(raw: &str) -> ValidationResult {
    let digits = strip_spaces_and_hyphens(kotlin_trim(raw));
    if digits.is_empty() {
        return ValidationResult::Invalid("Card number is required.");
    }
    if !digits.chars().all(kotlin_is_digit) {
        return ValidationResult::Invalid("Card number must contain digits only.");
    }
    let count = digits.chars().count();
    if !(13..=19).contains(&count) {
        return ValidationResult::Invalid("Card number must be between 13 and 19 digits.");
    }
    // NOTE: the source's Luhn loop indexes digits[i].digitToInt() on UTF-16
    // units. Non-ASCII BMP Nd digits pass isDigit above; digitToInt maps any
    // Nd digit to its numeric value, so char-level iteration is equivalent.
    let nums: Vec<u32> = digits
        .chars()
        .map(|c| crate::kotlin_text::kotlin_digit_to_int_10(c).expect("Nd digit"))
        .collect();
    let mut sum = 0u32;
    let mut alternate = false;
    for i in (0..nums.len()).rev() {
        let mut n = nums[i];
        if alternate {
            n *= 2;
            if n > 9 {
                n -= 9;
            }
        }
        sum += n;
        alternate = !alternate;
    }
    if sum % 10 != 0 {
        return ValidationResult::Invalid("Invalid card number. Checksum verification failed.");
    }
    ValidationResult::Valid
}

/// Validates MM/YY expiry against the caller-supplied current year-month
/// (source seam: YearMonth.now()). current is (year, month), e.g. (2026, 11).
pub fn validate_card_expiry(raw: &str, current: (i32, u32)) -> ValidationResult {
    let trimmed = kotlin_trim(raw);
    if trimmed.is_empty() {
        return ValidationResult::Invalid("Expiry date is required.");
    }
    let parts: Vec<&str> = trimmed.split('/').collect();
    if parts.len() != 2 {
        return ValidationResult::Invalid("Use MM/YY format (e.g. 12/28).");
    }
    let month = match kotlin_to_int_or_null(parts[0]) {
        Some(m) if (1..=12).contains(&m) => m as u32,
        _ => return ValidationResult::Invalid("Month must be between 01 and 12."),
    };
    let year_part = kotlin_to_int_or_null(parts[1]);
    if year_part.is_none() || parts[1].chars().count() != 2 {
        return ValidationResult::Invalid("Year must be 2 digits (e.g. 28).");
    }
    let full_year = 2000 + year_part.unwrap();
    // YearMonth.of(fullYear, month) is always valid here (month 1..12); the
    // source's catch branch is unreachable for these inputs.
    let (cy, cm) = current;
    if (full_year, month) < (cy, cm) {
        return ValidationResult::Invalid("This card has expired.");
    }
    ValidationResult::Valid
}

/// CVV: 3 or 4 digits (BMP Nd per Kotlin isDigit).
pub fn validate_card_cvv(raw: &str) -> ValidationResult {
    let trimmed = kotlin_trim(raw);
    if trimmed.is_empty() {
        return ValidationResult::Invalid("CVV is required.");
    }
    if !trimmed.chars().all(kotlin_is_digit) {
        return ValidationResult::Invalid("CVV must be numeric.");
    }
    if !(3..=4).contains(&trimmed.chars().count()) {
        return ValidationResult::Invalid("CVV must be 3 or 4 digits.");
    }
    ValidationResult::Valid
}
