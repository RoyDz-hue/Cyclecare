# Float oracle corpus (data_export_pdf::java_float_to_string)

`float_oracle.txt` holds 145,606 expected `Float.toString` outputs produced by
EXECUTING `Floats.java` on jdk-17.0.21+9 (Windows/Linux JDK 17, java.base
jdk.internal.math.FloatingDecimal). The sweep:

- integers -2000..=2000 (4,001)
- i/100f for i in 0..=10000 (10,001)
- bbt grid 34.00..=42.00 by 0.01 (801)
- sleep grid 0..=96 by 0.25 (97)
- i/1000f for i in 1..=1000 (1,000)
- 1,000,000 LCG-generated f32 bit patterns (seed 0x12345678, constants
  6364136223846793005/1442695040888963407, top 32 bits), filtered to the
  plain decimal range [1e-3, 1e7), finite, nonzero (129,706 kept)

The Rust test `tests/float_oracle.rs` regenerates the identical sweep and
zips it against this file. Zero differences observed against JDK 17.

Scope: PLAIN-RANGE ONLY. Scientific-notation magnitudes (< 1e-3, >= 1e7)
remain outside the verified contract and are labeled as such in the source.
Android ART Float.toString is NOT covered by this corpus.
