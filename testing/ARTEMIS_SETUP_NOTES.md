# ARTEMIS automated testing - research verdict + hard privacy boundary (2026-10-25)

## Verdict
Doable, but not fully inside the agent sandbox: the sandbox has no KVM, 2 vCPU, 1.9GB RAM - an Android emulator is not viable there. ARTEMIS's brain (server + LLM loops) can run in the sandbox; the device must live elsewhere: (a) Roy's machine + phone/emulator driven via ARTEMIS HTTP API, (b) a cloud device with adb-over-internet, or (c) Firebase Test Lab Robo for free crash-smoke only. ARTEMIS needs a Gemini API key; its coordinate-grounding node requires the Gemini ER model (gemini-robotics-er-2-preview). Cost: ~$0.04-0.10/Flash run, $0.20-0.60/Pro run; free AI Studio tier likely covers light use. Repo has no tagged releases - pin a commit SHA.

## HARD BOUNDARY (privacy review, mandatory)
The free Gemini tier's terms allow inputs/outputs to be used to improve Google products, incl. human review. CycleCare screenshots can carry health data even in a harmless test prompt. Therefore the testing loop is SYNTHETIC-ONLY:
- Clean test device/profile, never Roy's daily-use populated app.
- A synthetic CycleCare account with fake data only.
- No personal Google account on the test device.
- No private notifications or real member content in any screenshot/trace/video.
- Test-account creation honors the no-test-emails rule: email confirmation OFF, test users deleted afterward (Supabase admin cleanup).
- If real sensitive data were ever genuinely needed, that requires separate account terms and explicit clearance first - a paid key alone does not authorize it.
