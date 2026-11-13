# Tone language engine (preserved reference)

`LanguageEngine.kt` is Roy's tone framework: phase-aware greetings and
confidence wording keyed to the user's tone preference. It is not wired into
any screen yet, so per Roy's rule it lives here as a recipe, outside the
compiled app source set. When a surface needs it, copy it into
`Androidapp/app/src/main/java/com/cyclecare/app/domain/language/` and wire it.
