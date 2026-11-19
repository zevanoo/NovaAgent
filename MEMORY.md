# MEMORY.md — NovaAgent project log

## Status
Milestone aktif: **M4 — Memory file**. M0–M3 selesai, APK signed di CI.

## Milestone
- [x] M0 — Scaffolding (APK debug terbangun di CI, signed, terverifikasi)
- [x] M1 — UI/Provider (chat UI, settings provider, Room session, 6 slash command)
- [x] M2 — Termux bridge (RUN_COMMAND + read_file/write_file/list_dir/run_shell + agent loop)
- [x] M3 — Foreground service + memory cap (loop di service, ulimit -v, watchdog)
- [ ] M4 — Memory file
- [ ] M5 — Sistem skill
- [ ] M6 — File browser
- [ ] M7 — Polish & stabilitas

## Log

### M3 — Foreground service + memory cap (2026-11-19)
Dibuat:
- `AgentService` sekarang pemilik agent loop, bukan UI lagi. `onStartCommand`
  dengan action `com.novaagent.app.RUN_TURN` + extra `sessionId`; history dibaca
  dari Room, hasil ditulis balik ke Room, jadi turn selamat walau Activity mati.
- `AgentState` jadi bus service ↔ UI: `running`, `busy`, `status`, `error`,
  `turnDone`. UI menunggu `turnDone` lalu reload dari Room — tidak ada state
  duplikat yang bisa basi.
- Notifikasi persisten menampilkan baris tool yang sedang jalan
  (`→ run_shell: ...`), bukan teks statis.
- Watchdog `withTimeoutOrNull` 10 menit untuk seluruh turn; timeout per command
  (default 120s) tetap ada di `TermuxBridge`.
- `data/Limits.kt` — batas memori MB (0 = tanpa batas), diterapkan sebagai
  `ulimit -v <MB*1024>` di setiap `run_shell`. Exit 137 / "Cannot allocate memory"
  diterjemahkan jadi pesan jelas, bukan stderr mentah.
- `ChatViewModel` menyusut: kirim pesan = tulis Room + `AgentService.startTurn()`,
  plus `stopTurn()` yang mematikan service.
- `SettingsScreen`: slider batas memori (langkah 64 MB, 0..2048 MB).
- `ChatScreen`: baris status live + tombol Stop selama `busy`.

Verifikasi:
- CI run `44477334739` (commit `6e1ecb3`) **success**. APK 9.729.752 bytes,
  10 dex, sha256 `2d2f785019e311041c2fefd0e04403c45664744a3dc8c1d5992695cd70879edd`.
- **Belum diverifikasi di device:** turn yang bertahan saat app di-background,
  `ulimit` benar-benar menahan perintah boros RAM, watchdog.
- Catatan desain: `ulimit -v` membatasi *virtual* address space, jadi ia bisa
  membunuh proses yang sebenarnya ramah RSS (mis. JVM/Node). Jangan set default
  rendah — 0 (tanpa batas) tetap default.

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
- **Room adalah satu-satunya sumber kebenaran transcript.** Service menulis hasil
  turn ke Room; UI reload dari Room saat `turnDone` berubah. Tidak ada buffer
  pesan yang di-share antara UI dan service — itu sumber bug "balasan hilang".
- **Agent loop di service sejak M3**, bukan di `viewModelScope`. Activity mati
  bukan alasan turn ikut mati; notifikasi persisten jadi tempat status hidup.
- **`ulimit -v` default 0 (tanpa batas).** Batasi *virtual* memory, jadi nilai
  rendah bisa membunuh proses yang RSS-nya kecil (JVM/Node). User mengatur sendiri
  di Settings; app hanya menyediakan rem, bukan menebak angka aman.
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

## M4 — Memory file + workdir 3 tingkat + turn limit

**Selesai dan build hijau.** Run `44521703598` (commit remote `bfcea39`), APK
9.764.220 bytes, sha256 `5ba584f90547de884c08cddffb83e7290b4b4ba7f124e48531a90062853ed710`.

- `MemoryFiles.kt` — dua lapis sesuai PRD 4: `filesDir/memory/global/SOUL.md` +
  `USER.md` (selalu di system prompt), `filesDir/memory/projects/<slug>/MEMORY.md`
  (hanya dimuat kalau workdir resolve ke project itu). Tidak ada `PROJECTS.md`;
  ringkasan lintas project sengaja dilepas supaya global tetap personalisasi.
- Kebijakan tulis memory di `Limits.memoryWritePolicy`: `auto` atau `confirm`
  (default confirm). `confirm` menyimpan catatan di SharedPreferences sebagai
  pending, UI chat menampilkan banner Simpan / Tolak. Agent menulis lewat tool
  `save_memory`; `/memory approve|reject <target>` juga tersedia.
- `Workspace.kt` — resolusi `sesi > project > main`, `known()` untuk dropdown
  folder, disimpan per sesi di kolom `sessions.workdir`.
- `Limits.maxRounds` default **20**; watchdog `withTimeoutOrNull` dihapus total.
  Saat limit tercapai: notifikasi `notifyLimit` dengan aksi Lanjut / Prompt baru,
  dan catatan otomatis ditambahkan ke MEMORY.md project.
- Room v2 + `MIGRATION_1_2`: `sessions.workdir`, `messages.rounds`.
- `ChatScreen` — baris tool di bawah balasan, tap baris = tampil/sembunyi hasil,
  tap baris thought = tampil/sembunyi detail.

Keputusan teknis:
- `kotlinx-serialization` dibuang. Plugin tidak resolve di root `plugins {}`
  tanpa version (`Plugin [id: 'org.jetbrains.kotlin.plugin.serialization'] was not
  found`), dan menambah plugin + dependency untuk satu encode/decode JSON tidak
  sepadan. Pakai `org.json` yang sudah dipakai seluruh project.
- `prune()` memotong riwayat ke 60 pesan (2 pertama + ekor) supaya request tidak
  membengkak tanpa membuang konteks pembuka.
- "lanjut" me-replay notice limit sebagai pesan user `lanjut`, bukan sebagai
  ucapan model — supaya history provider tetap jujur.

Error yang ditemui:
- `Returns are prohibited for functions with an expression body` (`fileFor`) —
  ganti ke block body.
- `Suspend function 'messages' should be called only from a coroutine`
  (`buildHistory`) — tambah `suspend`.
- `git push` dari Windows gagal: credential manager minta GUI, tidak ada tty
  (`/dev/tty: No such device or address`), `ls-remote` pun kena. Push lewat
  `tools/gh_push_rest.py` (Git Data API, satu commit per HEAD, parent = remote
  main, token dari env `GH_TOKEN`).

**Belum diverifikasi on-device.** Yang perlu dicek user: memory project benar
hanya termuat untuk workdir-nya, banner konfirmasi memory muncul, dan limit 20
memicu notifikasi Lanjut / Prompt baru.

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

## M5 — selesai (skills, workdir rework, UI/UX, font)

Status: **green**. Run CI `44560790563`, commit remote `c0a4f1f`.
APK debug **10,709,123 B**, sha256
`c018034812590b066e92d356a97920c6717ec5e3b1bac2b12a94e206b6964c30`.

### Workdir (dirombak sesuai koreksi user)
- Setting hanya **satu**: `defaultDir` (folder induk). Tidak ada main/project workdir lagi.
- `Workspace.kt` menyimpan `sessionDirs` sebagai peta `id=path` di SharedPreferences;
  `resolve(context, sessionId)` mengembalikan folder sesi, fallback ke default.
- Folder sesi dibuat **lazy** oleh `AgentService` lewat Termux `RunCommandService`
  saat tool pertama yang menulis dipanggil (`write_file`, `run_shell`, `save_memory`).
  Tool read-only tetap di default dir → chat tanpa tulis tidak bikin folder kosong.
- Nama folder: `<slug topik sesi>` di bawah default dir; dibuat sekali, disimpan di pref.
- Workdir terlihat di header chat (chip) — `AgentState.sessionDir` +
  `AgentState.workdirVersion` untuk invalidasi tanpa buka Settings.

### Skill (M5 inti)
- `data/Skills.kt`: tiga root — `<workdir>/.novaagent/skills`, `filesDir/skills`,
  `filesDir/bundled-skills`. Frontmatter YAML minimal (name, description) di-parse sendiri.
- `index()` → nama + deskripsi (`name — desc`), `body()` → isi SKILL.md penuh.
- System prompt (`MemoryFiles.systemPrompt`) memuat `## Skill tersedia` berisi indeks +
  instruksi memanggil `use_skill` dulu sebelum mengerjakan task yang cocok.
- Tool baru `use_skill(name)` di `AgentTools`; body masuk transcript sebagai tool result.
- Settings: daftar skill + tombol Hapus; install manual = taruh folder di `filesDir/skills`.

### UI/UX
- `ui/theme/Theme.kt` ditulis ulang: ColorScheme gelap/terang (slate dalam, primary
  hijau `0xFF7DD3A0`), `Spacing` tokens, typography dari `AppFonts`.
- `ui/ChatScreen.kt` ditulis ulang total (539 baris) gaya Claude Android:
  header ringkas + chip workdir, baris tool/thought **collapsible** di bawah turn user,
  banner memory pending + banner limit, composer `imePadding`, tombol kirim dim saat kosong.
- `ui/Markdown.kt` baru: bold/italic/inline code/fenced code (scroll horizontal),
  `SelectionContainer`.
- `ui/SettingsScreen.kt` ditulis ulang: Provider, Workdir (satu field), Tampilan (font),
  Skill, Batas agent, Memory, Termux. Hanya yang tersedia.

### Font
- Asset bawaan: `assets/fonts/{body,heading,mono}/` → Inter, Inter Tight, JetBrains Mono.
- `ui/theme/AppFonts.kt`: peran `body`/`heading`/`mono`; override user di
  `filesDir/fonts/<role>/`; `install()` (salin dari SAF picker) / `reset()`.
  Typeface di-cache per key file+mtime; folder user diutamakan di atas asset.
- **Jebakan**: `androidx.compose.ui.text.font.Font("path")` hanya untuk `res/font`,
  BUKAN asset → `Typeface.createFromAsset()` lalu `FontFamily(typeface)`.
- **Jebakan**: regex inline markdown harus raw string Kotlin (`"""`),
  `\n`/`\*` di string biasa = `Unsupported escape sequence`.
- **Jebakan**: `AnnotatedString.Builder.withStyle` tidak ada; pakai
  `builder.addStyle(style, start, end)`.
- **Jebakan**: `TextOverflow.MiddleEllipsis` tidak ada di compose versi ini → `Ellipsis`.

### Sisa / belum terverifikasi di device
- Belum ada uji di perangkat sejak M4: skill index benar sampai ke model, `use_skill`
  mengembalikan body, font user benar-benar terpakai, folder sesi terbentuk.
- API key masih di memori proses (EncryptedSharedPreferences belum).
