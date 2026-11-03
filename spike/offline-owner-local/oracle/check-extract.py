from pathlib import Path
import json,hashlib,sys
r=Path(sys.argv[1]);o=Path(__file__).resolve().parent;s=r/'Androidapp/app/src/main/java/com/cyclecare/app'
manifest=json.loads((o/'source-manifest.json').read_text())
for f,h in manifest.items():assert hashlib.sha256((s/f).read_bytes()).hexdigest()==h,f
x=(s/'data/local/HealthSyncLocal.kt').read_text();body=x[x.index('object HealthSyncPayloadCodec {'):]
a=(o/'Codec.kt').read_text();assert a[a.index('object HealthSyncPayloadCodec {'):]==body
v=(s/'ui/viewmodel/CycleCareViewModel.kt').read_text();start=v.index('            if (log.flowLevel in listOf');rule=v[start:v.index('\n\n            val safety',start)];assert rule==(o/'anchor-rule-source.txt').read_text()
rule=rule.replace('''                    val updatedSettings = _uiState.value.settings.copy(
                        lastPeriodStartDate = log.dateString
                    )
                    repository.saveSettings(updatedSettings)''','''                    return log.dateString''').replace('_uiState.value.settings.lastPeriodStartDate','settings.lastPeriodStartDate')
expected='import com.cyclecare.app.data.local.*\nimport java.time.LocalDate\nfun anchor(log:CycleDayLog,settings:CycleSettings):String? {\n'+rule+'\nreturn null\n}\n'
assert (o/'AnchorOracle.kt').read_text()==expected
assert (r/'Androidapp/rust/core/src/sync_codec.rs').read_bytes()==(o.parent/'rust/src/sync_codec.rs').read_bytes()
print('PASS exact current codec extraction, anchor control-flow extraction (effect replaced with return), source manifest and copied Rust codec')

for name,path in [("CareConversationPolicy.kt","domain/ai/CareConversationPolicy.kt"),("NaturalLanguageLogParser.kt","domain/parser/NaturalLanguageLogParser.kt")]:
 assert (o/'care'/name).read_bytes()==(s/path).read_bytes()
v=(s/'data/repository/CycleRepository.kt').read_text();a=v.index('            val changed = read?.copy(',v.index('    suspend fun updateSettingsBound('));b=v.index('\n            journalMutation',a);rule=v[a:b]
assert (o/'settings-rule-source.txt').read_text()==rule
assert 'OnboardingProfile? = '+rule.replace('            val changed = ','') in (o/'SettingsOracle.kt').read_text()
for f in ['care_policy.rs','log_parser.rs']:
 assert (r/'Androidapp/rust/core/src'/f).read_bytes()==(o.parent/'rust/src'/f).read_bytes()
print('PASS byte-exact current care/parser source, settings profile expression, current Rust care/parser modules')
