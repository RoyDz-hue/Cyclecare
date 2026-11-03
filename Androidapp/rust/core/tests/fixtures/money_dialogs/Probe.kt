import java.io.File
import java.math.BigDecimal

// Verbatim decision chains: CoinTransferDialog :146-161, InsufficientCoinsDialog :61,
// MpesaStkPushDialog :142/:221/:230
fun transferDecision(recipientEmail: String, transferAmountText: String, currentBalance: BigDecimal): String {
    val amountText = transferAmountText.trim()
    if (recipientEmail.isBlank() || !recipientEmail.contains("@")) {
        return "ERR|Please enter a valid registered email address."
    } else if (!Regex("""^\d+(\.\d{1,3})?$""").matches(amountText)) {
        return "ERR|Enter an amount with up to 3 decimal places."
    } else {
        val amount = amountText.toBigDecimal()
        if (amount <= BigDecimal.ZERO) {
            return "ERR|Please enter an amount greater than zero."
        } else if (amount > currentBalance) {
            return "ERR|Amount exceeds your available balance (${com.cyclecare.app.util.CoinFormatter.format(currentBalance)} coins)."
        } else {
            return "SEND|$recipientEmail|$amount"
        }
    }
}

fun missingCoins(price: Long, userCoins: BigDecimal) = (BigDecimal.valueOf(price) - userCoins).coerceAtLeast(BigDecimal("0.001"))
fun mpesaEnabled(phone: String, priceKes: Long) = phone.trim().length >= 10 && priceKes > 0L
fun priceLabel(priceKes: Long) = if (priceKes > 0L) "KES $priceKes" else "M-Pesa price unavailable"

fun main() {
    val out = StringBuilder()
    val bal = BigDecimal("10.500")
    out.append(transferDecision("sam@x.io", "5", bal)).append('\n')
    out.append(transferDecision("samx.io", "5", bal)).append('\n')
    out.append(transferDecision("  ", "5", bal)).append('\n')
    out.append(transferDecision("", "5", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "5.", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "5.1234", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "abc", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "-5", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "1,5", bal)).append('\n')
    out.append(transferDecision("sam@x.io", ".5", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "007", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "5.250", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "0", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "0.000", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "0.001", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "10.500", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "10.501", bal)).append('\n')
    out.append(transferDecision("sam@x.io", "11", BigDecimal("1234567.891"))).append('\n')
    out.append(transferDecision("sam@x.io", "  5  ", bal)).append('\n')
    out.append(transferDecision("  sam@x.io  ", "5", bal)).append('\n') // raw email passed through
    out.append("---missing---\n")
    out.append(missingCoins(50, BigDecimal("20")).toString()).append('\n')
    out.append(missingCoins(50, BigDecimal("49.9995")).toString()).append('\n')
    out.append(missingCoins(50, BigDecimal("50")).toString()).append('\n')
    out.append(missingCoins(50, BigDecimal("60")).toString()).append('\n')
    out.append("---mpesa---\n")
    out.append(mpesaEnabled("0712345678", 100)).append('\n')
    out.append(mpesaEnabled("0712", 100)).append('\n')
    out.append(mpesaEnabled("  0712345678  ", 100)).append('\n')
    out.append(mpesaEnabled("0712345678", 0)).append('\n')
    out.append(mpesaEnabled("0712345678", -5)).append('\n')
    out.append(priceLabel(100)).append('\n')
    out.append(priceLabel(0)).append('\n')
    File("/tmp/moneyprobe/expected.txt").writeText(out.toString())
}
