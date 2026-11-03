# DebugBridgeConfiguration JDK17 differential oracle

`Bridge.java` re-expresses DebugBridgeConfiguration.baseUrl (main@31b88f5)
verbatim against java.net.URI and was compiled+run with the local
jdk-17.0.21+9. `inputs.txt` holds 649 boundary candidates (schemes,
hostname edge shapes, IPv4 range/leading-zero/trailing-dot cases, IPv6
compression/embedded-IPv4/zone-id forms, ports incl. empty/overflow,
paths/query/fragment/userinfo, whitespace incl. NBSP - note the harness
uses Kotlin trim semantics: Character.isWhitespace OR isSpaceChar, since
Kotlin's isWhitespace includes NBSP where Java's does not).
`expected.txt` is the executed JDK17 output, one result per input line
("NULL" for null). tests/debug_bridge_corpus.rs replays all 649 lines
through the ported parser and requires exact agreement.

LIMIT: JDK17 java.net.URI, not Android's implementation; the Android-vs-
JDK gap is documented on the module.
