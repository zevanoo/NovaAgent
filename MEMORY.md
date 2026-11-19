# MEMORY.md — NovaAgent project log

## Status
Milestone aktif: **M3 — Foreground service + memory cap**. M0–M2 selesai, APK signed di CI.

## Milestone
- [x] M0 — Scaffolding (APK debug terbangun di CI, signed, terverifikasi)
- [x] M1 — UI/Provider (chat UI, settings provider, Room session, 6 slash command)
- [x] M2 — Termux bridge (RUN_COMMAND + read_file/write_file/list_dir/run_shell + agent loop)
- [ ] M3 — Foreground service + memory cap
- [ ] M4 — Memory file
- [ ] M5 — Sistem skill
- [ ] M6 — File browser
- [ ] M7 — Polish & stabilitas

## Log

### M2 — Termux bridge (2026-11-19)
Dibuat:
- `bridge/TermuxBridge.kt` — `Intent("com.termux.RUN_COMMAND")` dikirim lewat
  `startService` (bukan `startActivity`; RunCommandService-nya service, jadi
  `startActivity` bakal gagal). RUN_COMMAND fire-and-forget: command dibungkus
  menulis `out.txt` + `code.txt` ke `/sdcard/novaagent/run/<id>/`, lalu dipoll
  sampai `code.txt` muncul (timeout default 120s). Output digabung stdout+stderr.
- `agent/AgentTools.kt` — 4 tool: `read_file` (offset/limit), `write_file`,
  `list_dir`, `run_shell` (timeout_ms). File-IO dijalankan di dalam Termux juga,
  supaya shell dan file punya namespace yang sama (path app-sandbox ≠ path Termux).
  Allowlist: `/sdcard`, `/storage/emulated/0`, HOME Termux, tmp Termux; `~` dan
  path relatif di-expand lalu `canonicalFile` (menahan `..`).
- `agent/AgentLoop.kt` — model → tool_calls → hasil → ulangi, maks 6 putaran.
  `onTool` callback dipakai UI untuk menandai tool yang jalan.
- `net/LlmClient.kt` — `chatWithTools` mengembalikan `Reply(content, toolCalls, raw)`;
  `raw` (pesan assistant mentah) wajib di-echo utuh di request berikutnya, kalau
  direka ulang provider menolak.
- `data/Workspace.kt` — working dir tool disimpan di SharedPreferences.
- `ui/ChatViewModel` — loop agent di `viewModelScope` (BUKAN pindah ke service;
  itu M3), `apiHistory()` membangun ulang history dari Room dan membuang marker tool.
- `UiMessage` dapat `id` supaya LazyColumn punya key stabil.
- Command `/termux [path]` + panel status bridge di Settings.
- Manifest: `com.termux.permission.RUN_COMMAND`, `MANAGE_EXTERNAL_STORAGE`.

Verifikasi:
- CI run `44473382027` (commit `e56c03d`) **success**. APK 9.712.960 bytes,
  10 dex, sha256 `d6e6f8ed669f5e1701eef3486861b8279f41ab379a4a35b82c1accca1058189e`,
  signed v2 `CN=Android Debug`. Permission dicek ada di manifest biner:
  `RUN_COMMAND`, `MANAGE_EXTERNAL_STORAGE`, `INTERNET`, `queried com.termux.api`.
- **Belum diverifikasi di device:** tool call nyata ke Termux. Butuh
  `allow-external-apps=true` di `~/.termux/termux.properties` + restart Termux,
  dan izin storage dari app.
- Catatan: `aapt2` dari Android SDK x86_64, jadi verifikasi manifest dilakukan
  dengan membaca `AndroidManifest.xml` di dalam APK (string UTF-16) dari Python.

Bug CI yang ketemu (sudah fix):
- `"$HOME Termux"` di string Kotlin → interpolasi variabel `HOME` yang tidak ada.
- `Settings` dipakai di `AgentLoop` tanpa import.

### M1 — UI/Provider (2026-11-19)
Dibuat:
- `data/ChatDb.kt` — Room: `Session`, `Message`, `ChatDao`, singleton `ChatDb`.
- `data/Settings.kt` — `ProviderConfig` (+ `chatEndpoint`, `isUsable`), object
  `Settings` (`StateFlow<ProviderConfig>`), data class `UiMessage`.
- `data/MemoryFiles.kt` — baca/status `SOUL.md`/`USER.md`/`MEMORY.md` di
  `filesDir/memory/`.
- `net/LlmClient.kt` — POST OpenAI-compatible `/v1/chat/completions`,
  `HttpURLConnection`, error HTTP dibawa sebagai pesan (bukan crash).
- `ui/ChatViewModel.kt` — kirim pesan, persist ke Room, judul sesi otomatis dari
  pesan user pertama, dispatcher command.
- `ui/ChatScreen.kt` — drawer daftar sesi, bubble user/assistant (SelectableText),
  indikator busy, baris error, input multiline.
- `ui/SettingsScreen.kt` — field Base URL / API key (masked) / Model + preview
  endpoint, switch foreground service, status memory, catatan soal API key.
- `MainActivity` — `Root()` dengan `BackHandler`, navigasi chat ↔ settings.
- Hapus `ui/HomeScreen.kt` (placeholder M0, tombol service pindah ke Settings).
- Manifest: `INTERNET`, `usesCleartextTraffic`.
- Gradle: KSP `2.0.21-1.0.28`, Room 2.6.1 (+room-ktx, room-compiler via ksp),
  kotlinx-coroutines-android, lifecycle-viewmodel-compose.

Verifikasi:
- CI run `44469382067` (commit `1c7e164`) **success**. APK 9.696.391 bytes,
  8 dex, sha256 `9606629a4cecdbdc306ebf5fb6e2a89a0d3193262bb1867fea9d17811014c686`,
  signed v2 `CN=Android Debug`.
- Belum diverifikasi: perilaku runtime di device (kirim pesan ke provider asli,
  persistensi sesi lintas restart, `/compact`).

Bug yang ketemu di CI (sudah fix):
- `ChatViewModel`: `sysPrompt` pakai parameter konstruktor `app` di getter →
  `Unresolved reference 'app'`. Parameter konstruktor hanya terlihat di
  initializer/property initializer, bukan di akses berikutnya. Pakai
  `getApplication()`.

### M0 — Scaffolding (2026-11-19)
Dibuat:
- Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21, Compose BOM 2024.10.01.
  `compileSdk 35`, `minSdk 26`, `targetSdk 35`, namespace `com.novaagent.app`.
- Struktur package: `ui/` (+`ui/theme/`), `data/`, `service/`.
- `MainActivity` — single Activity, Compose entry, minta `POST_NOTIFICATIONS` (API 33+).
- `ui/HomeScreen` — judul + tombol Start/Stop service + indikator status.
- `ui/theme/Theme.kt` — Material3, palet hangat gelap sebagai default.
- `service/AgentService` — foreground service kosong (belum ada agent loop),
  `START_STICKY`, notifikasi persisten `IMPORTANCE_LOW` + intent balik ke app.
- `data/AgentState` — `MutableStateFlow<Boolean>` status service (sementara, diganti
  jadi Service binding/StateFlow di M3).
- Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
  `POST_NOTIFICATIONS`, `QUERY_ALL_PACKAGES`, `<queries>` untuk `com.termux` &
  `com.termux.api`.
- Ikon: adaptive icon (vector), warna `#D97757`.

## Keputusan
- **Tool file-IO dijalankan lewat Termux**, bukan langsung dari app. Kalau
  `read_file` pakai `java.io.File` di app sandbox, model melihat dua namespace
  berbeda (path app vs path Termux) dan hasilnya membingungkan. Satu namespace.
- **Hasil RUN_COMMAND lewat file di `/sdcard`**, karena intent-nya tak bisa
  mengembalikan hasil. Butuh `MANAGE_EXTERNAL_STORAGE`; kalau app di-reset izinnya,
  tool langsung memberi pesan jelas, bukan gagal senyap.
- **Allowlist path** (`/sdcard`, `/storage/emulated/0`, HOME + tmp Termux).
  Bukan sandbox sungguhan — model tetap bisa `run_shell` apa saja. Ini pembatas
  untuk kecelakaan path, bukan pertahanan terhadap prompt injection.
- **Non-streaming LLM call** untuk M1. SSE parsing tidak memberi nilai sebelum
  tool-calling ada (M2), dan satu balasan JSON jauh lebih sedikit kode. Upgrade
  ke `stream=true` kalau latensi token jadi masalah nyata.
- **Room** (bukan DataStore/JSON) untuk session & pesan: query per-session dan
  hapus riwayat jadi satu baris masing-masing. Versi 1, `exportSchema=false`.
- **Satu sesi bertahan** lintas restart (id di SharedPreferences). Sesi per-project
  menyusul bareng M6 (file browser).
- **API key belum dipersist** — hanya di memori proses (`Settings` flow). SOUL.md
  melarang simpan key plaintext; jadi itu nunggu EncryptedSharedPreferences.
- Client pakai `HttpURLConnection` + `org.json`, bukan OkHttp/Retrofit/moshi.
  Sudah ada di platform, tiga pemakaian belum membenarkan 3 dependency baru.
- Endpoint dirakit dari base URL: kalau sudah berakhir `/v1` tidak ditambah lagi.
- Command `/skill` hanya melist folder `filesDir/skills`; loader skill M5.
- Memory dibaca dari `filesDir/memory/`. `/memory` menampilkan status, tidak
  membuat file — auto-create M4.
- Ikon drawer/settings pakai teks `☰`/`⚙`, bukan `material-icons-extended`
  (dependency besar untuk dua glyph).
- `android:usesCleartextTraffic="true"` supaya provider lokal (`http://localhost`)
  bisa dites dari HP.
- `foregroundServiceType="specialUse"` — belum ada tipe yang pas untuk "agent loop";
  Android 14+ wajib deklarasi tipe. Revisit di M3 kalau perlu `dataSync`.
- `QUERY_ALL_PACKAGES` dipasang sesuai PRD, walau `<queries>` sebenarnya sudah cukup
  untuk Termux. Ditolak Play Store tanpa justifikasi, tapi ini sideload.
- Status service disimpan di `object` flow, bukan bound service — minimal untuk M0.
- `minSdk 26` — Compose + NotificationChannel butuh 26, cukup untuk Termux target.
- `foregroundServiceType` butuh `POST_NOTIFICATIONS` runtime (API 33+), jadi diminta
  saat app dibuka, bukan saat service start.

## Verifikasi
- **M1 build: BERHASIL.** Run `44469382067` (commit `1c7e164`), APK 9.696.391
  bytes, 8 dex, signed v2 `CN=Android Debug`. Copy lokal: `~/novaagent-debug.apk`.
- **M0 build: BERHASIL.** Run `44466188182` (commit `7d434ac`), artifact
  `novaagent-debug` berisi `app-debug.apk` 9.466.044 bytes (150 entry, 8 dex,
  lib arm64-v8a/armeabi-v7a/x86/x86_64). Signed v2, `CN=Android Debug`,
  SHA-256 cert `6ba90922f096a12235aff1fd8ad4b7c5dace252de4b6cd64b0967ae104e0d0bd`.
- **M0 runtime on-device: terverifikasi user** — app jalan, service start/stop OK.
- **M1 runtime on-device: belum diverifikasi.**
- Remote: `https://github.com/zevanoo/NovaAgent` (private). Push lewat GitHub REST
  API (`tools/gh_push.py`) karena Termux git di device ini tidak punya `remote-http`
  helper. Repo kosong kena 409, jadi script seed satu commit pembuang
  (`PUT /contents/.seed`) lalu `force` `refs/heads/main`. **Commit SHA di remote
  BEDA dari lokal** (tree/commit dibuat server-side) — jangan push dua arah.
- Helper CI: `tools/ci_log.py <run_id>` (ambil log job gagal),
  `tools/fetch_apk.py` (unduh APK run sukses terakhir + sha256).
- Build debug APK lokal (Termux): **masih gagal.** Gradle 8.9 di Termux fork ini:
  `Failed to load native library 'libnative-platform.so' for Linux aarch64`.
  Build-tools aapt2 Android juga x86_64, bukan aarch64.

## Blocker build lokal (kenapa CI yang jadi compiler)
- `java` sudah jalan: JDK 17 hasil ekstrak manual `.deb` di `$PREFIX/lib/jvm/java-17-openjdk`.
- CA store JDK kosong (lib/security/cacerts symlink rusak) → dibuat ulang dari
  `/system/etc/security/cacerts`; Gradle harus pakai `GRADLE_OPTS=-Djavax.net.ssl.trustStore=...`.
- `sdkmanager` tidak bisa unduh manifest (SSL) → platform-35 & build-tools di-extract manual.
- Root cause terkonfirmasi: `libnative-platform.so` aarch64 ada di jar, tapi butuh
  `libstdc++.so.6` yang tidak ada (Android cuma punya `/system/lib64/libstdc++.so`,
  nama beda). `ctypes.CDLL` gagal `dlopen: library "libstdc++.so.6" not found`.
- `aapt2` di `android-sdk/build-tools/35.0.0` = ELF x86_64, bukan aarch64.
- Git lokal tanpa `remote-http` helper; `curl` rusak
  (`cannot locate symbol ngtcp2_crypto_get_path_challenge_data2_cb`); `ssh` tidak ada.
- Tidak dikerjakan — semua build lewat GitHub Actions.
