# NovaAgent

Native Android agent app (Kotlin + Jetpack Compose). Runs an LLM tool loop, executes shell commands through Termux, and keeps its own files, sessions, memory, and skills inside the app's sandbox.

## Fitur

- **Agent loop milik service** — jalan di foreground service, tahan process death, ada state query/resume.
- **Multi provider + multi model** — OpenAI-compatible dan Anthropic-compatible, daftar model diambil dari `/v1/models`.
- **Fallback model** — model utama dicoba 4 kali, lalu pindah ke model fallback (4 kali).
- **Tool**: `run_shell`, `read_file`, `write_file`, `list_dir`, plus tool MCP.
- **Termux shell backend** — `com.termux.RUN_COMMAND` dengan delivery hasil lewat `PendingIntent`.
- **Command approval** — setiap command dinilai: sangat bahaya / bahaya / beresiko / netral / aman. Yang di bawah netral butuh persetujuan: allow once, allow session, always allow, reject. Tanpa jawaban 5 menit → auto reject.
- **MCP** — server Streamable HTTP, discover tool, token disimpan terenkripsi.
- **Skill marketplace** — pasang skill dari repo GitHub (HTTPS), dengan validasi host, batas ukuran, dan proteksi path traversal.
- **Memory** — global (`SOUL.md`, `USER.md`) dan per-project (`MEMORY.md`), ditulis otomatis setelah tiap turn.
- **Git snapshot + revert** — tiap turn punya commit snapshot; revert mengembalikan file workdir.
- **i18n** — Indonesia dan English.
- **API key** — disimpan permanen di Android Keystore (AES-GCM), bukan plaintext dan bukan cuma di RAM.

## Kebutuhan

- Android 8.0+ (minSdk 26), target SDK 35.
- **Termux** terpasang dari F-Droid.
- Di Termux: `~/.termux/termux.properties` berisi `allow-external-apps=true`, lalu restart Termux.
- Izin `com.termux.permission.RUN_COMMAND` diberikan ke NovaAgent.

## Build

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Build resmi berjalan di GitHub Actions (`.github/workflows/build.yml`) setiap push ke `main`; artifact `novaagent-debug` berisi APK.

## Release

- Tag: `v1.0.0`
- Asset APK diambil dari artifact CI. Build ini debug-signed — pasang dengan `adb install -r`, atau aktifkan "install unknown apps".

## Struktur

```
app/src/main/java/com/novaagent/app/
  agent/      AgentTools — eksekusi tool, sandbox, approval
  bridge/     TermuxBridge — RUN_COMMAND, polling hasil
  data/       Settings, SecretStore, MemoryFiles, Skills, ChatDb, Workspace
  net/        LlmClient — provider, fallback, model list
  service/    AgentService — loop agent, notifikasi, state
  ui/         Chat, Settings, drawer, tema
docs/         PRD.md, SOUL.md, MEMORY.md (dokumen pengembangan)
tools/        skrip push/CI/APK untuk alur kerja repo
```

## Data

Semua data aplikasi di `/sdcard/Android/data/com.novaagent.app/files/`:

- `projects/` — workdir project agent
- `memory/` — `SOUL.md`, `USER.md`, `memory/projects/<slug>/MEMORY.md`
- `skills/` — skill terpasang
- `termux-run/` — pertukaran output dengan Termux
- `novaagent.db` — riwayat sesi (Room)

## Lisensi

MIT — lihat [LICENSE](LICENSE).
