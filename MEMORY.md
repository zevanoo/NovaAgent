# MEMORY.md — NovaAgent project log

## Status
Milestone aktif: **M1 — UI/Provider**. M0 selesai — build APK terverifikasi di GitHub Actions.

## Milestone
- [x] M0 — Scaffolding (APK debug terbangun di CI, signed, terverifikasi)
- [ ] M1 — UI/Provider
- [ ] M2 — Termux bridge
- [ ] M3 — Foreground service + memory cap
- [ ] M4 — Memory file
- [ ] M5 — Sistem skill
- [ ] M6 — File browser
- [ ] M7 — Polish & stabilitas

## Log

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
- `foregroundServiceType="specialUse"` — belum ada tipe yang pas untuk "agent loop";
  Android 14+ wajib deklarasi tipe. Revisit di M3 kalau perlu `dataSync`.
- `QUERY_ALL_PACKAGES` dipasang sesuai PRD, walau `<queries>` sebenarnya sudah cukup
  untuk Termux. Ditolak Play Store tanpa justifikasi, tapi ini sideload.
- Status service disimpan di `object` flow, bukan bound service — minimal untuk M0.
- `minSdk 26` — Compose + NotificationChannel butuh 26, cukup untuk Termux target.
- `foregroundServiceType` butuh `POST_NOTIFICATIONS` runtime (API 33+), jadi diminta
  saat app dibuka, bukan saat service start.

## Verifikasi
- **M0 build: BERHASIL.** GitHub Actions run `44466188182` (commit `7d434ac`) —
  semua step success, artifact `novaagent-debug` berisi `app-debug.apk`
  **9.466.044 bytes** (150 entry, 8 dex, lib arm64-v8a/armeabi-v7a/x86/x86_64).
  Signed v2, `CN=Android Debug`, SHA-256 cert `6ba90922f096a12235aff1fd8ad4b7c5dace252de4b6cd64b0967ae104e0d0bd`.
  Copy lokal: `~/novaagent-debug.apk`.
- **Belum diverifikasi:** APK belum di-install dan dijalankan di device.
- Remote: `https://github.com/zevanoo/NovaAgent` (private), push lewat GitHub
  REST API — Termux git di device ini tidak punya `remote-http` helper, jadi
  `git push` https mustahil. Script: `~/.hermes/cache/scratch/gh_push.py`
  (rebuild chain lokal → blob/tree/commit API). Repo kosong kena 409, jadi
  script seed satu commit pembuang dulu (`PUT /contents/.seed`) lalu `force`
  `refs/heads/main`. Commit SHA di remote BEDA dari lokal (author/committer
  dipertahankan, tapi tree/commit dibuat server-side).
- Build debug APK lokal (Termux): **masih gagal.** Gradle 8.9 di Termux fork ini:
  `Failed to load native library 'libnative-platform.so' for Linux aarch64`
  (IOException "No such file or directory" saat dlopen). Build-tools aapt2 Android
  juga x86_64, bukan aarch64 → `aapt2` perlu override ke binary Termux.
- Runtime on-device: belum diverifikasi (device non-debuggable, perlu install manual).

## Blocker build (untuk M0 lanjutan)
- `java` sudah jalan: JDK 17 hasil ekstrak manual `.deb` di `$PREFIX/lib/jvm/java-17-openjdk`.
- CA store JDK kosong (lib/security/cacerts symlink rusak) → dibuat ulang dari
  `/system/etc/security/cacerts`; Gradle harus pakai `GRADLE_OPTS=-Djavax.net.ssl.trustStore=...`.
- `sdkmanager` tidak bisa unduh manifest (SSL) → platform-35 & build-tools di-extract manual.
- Butuh: wrapper Gradle yang jalan (buildkit fork) atau patchelf libnative-platform.
- Root cause terkonfirmasi: `libnative-platform.so` aarch64 ada di jar, tapi butuh
  `libstdc++.so.6` yang tidak ada di sistem (Android pakai libc++). `ctypes.CDLL`
  gagal `dlopen: library "libstdc++.so.6" not found` — bukan masalah arsitektur.
  Fix lokal kalau mau: sediakan `libstdc++.so.6` yang kompatibel (belum dicek pkg
  Termux mana yang menyediakannya) atau rebuild native-platform dari source.
  Tidak dikerjakan — build dipindah ke CI.

