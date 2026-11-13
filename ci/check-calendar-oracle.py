#!/usr/bin/env python3
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"Androidapp/app/src"
a=(root/"main/java/com/cyclecare/app/data/repository/CycleRepository.kt").read_text()
a=a[a.index("    fun calculateDayPrediction("):a.index("\n    companion object",a.index("    fun calculateDayPrediction("))]
b=(root/"lab/java/com/cyclecare/app/lab/CalendarKotlinOracle.kt").read_text()
b=b[b.index("    fun calculateDayPrediction("):b.rindex("\n}")]
assert a==b,"Calendar oracle drift: must match original production function verbatim"
print("PASS: lab oracle equals production Kotlin function verbatim; constructors not instantiated")
