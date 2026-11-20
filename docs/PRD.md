# NovaAgent — Product Requirements Document

*18 November 2026*

## Ringkasan

NovaAgent adalah aplikasi Android native yang bertindak sebagai coding agent (mirip Claude Code), tapi mendukung LLM provider custom dan mengeksekusi tool secara lokal di HP lewat Termux. Tujuannya: pengguna bisa mengerjakan task yang butuh AI agent tanpa perlu membuka laptop.

## Masalah

Saat ini eksekusi agent di Android hanya bisa lewat Termux + OpenCode (community package), yang berbasis CLI, setup manual, dan tidak persisten kalau HP di-reset. Tidak ada UI native yang terintegrasi dengan provider custom milik pengguna.

## Tujuan (Goals) — v1

- Chat UI native ala Claude App Android, terhubung ke LLM provider custom (format OpenAI-compatible / Anthropic-compatible)
- Tool-calling dasar (read/write file, list directory, run shell) via Termux:API
- App tidak force-close sendiri: foreground service + notifikasi persisten
- Memory cap yang bisa diatur user untuk command yang dijalankan (ulimit per proses)
- Memory agent berbasis file: `SOUL.md` & `USER.md` global (personalisasi user, berlaku lintas project), `MEMORY.md` per project (konteks project yang sedang dikerjakan) — gaya Hermes Agent
- Skill bawaan yang relevan untuk dev + jalur instalasi skill tambahan secara manual
- File browser ringan untuk melihat struktur project

## Non-Goals (ditunda ke versi berikutnya)

- Eksekusi remote (VPS) — disebut sebagai opsi B di tahap konsep, belum masuk v1
- Marketplace skill in-app
- Multi-agent / sub-agent
- Voice input
- Integrasi git

## Arsitektur

```
[NovaAgent App]
  - UI Chat (Compose) + Prompt Builder + Session/Memory store (Room)
  - Foreground Service (agent loop + notifikasi persisten)
        |
        v  (Termux:API — RUN_COMMAND intent)
[Termux]
  - Eksekusi shell dengan `ulimit -v <cap>` per command
  - Read/write file project
        |
        v  (HTTP, format OpenAI/Anthropic-compatible)
[LLM Provider Custom]
  - Langsung, atau lewat NovaRouter (proxy milik pengguna)
```

Eksekusi tool 100% lokal di HP — tidak ada komponen server wajib di v1.

## Komponen Utama

### 1. Provider & Chat

Settings untuk base URL, API key, model name. Chat UI dengan bubble pesan, blok kode, drawer daftar sesi, dropdown model/provider — mengikuti pola layout Claude App Android.

### 2. Eksekusi Tool (Termux bridge)

Tool calling dasar (read file, write/edit file, list directory, run shell) dikirim lewat Termux:API `RUN_COMMAND` intent, hasil stdout/stderr dikembalikan ke app.

### 3. Foreground Service & Memory Cap

Agent loop berjalan di `Service` dengan notifikasi persisten (permission `FOREGROUND_SERVICE`, exemption battery optimization). Setiap command dibungkus `ulimit -v <cap>` sesuai slider yang diatur user di Settings; kalau proses kena limit, app menampilkan pesan jelas (bukan error mentah).

### 4. Memory File

Memory dibagi dua lapis: global (personalisasi user, berlaku di semua project) dan per-project (konteks project yang sedang dikerjakan saja):

- `SOUL.md` (global) — persona/system prompt dasar agent
- `USER.md` (global) — fakta & preferensi pengguna, konsisten di semua project
- `MEMORY.md` (per-project) — log/konteks project yang sedang dikerjakan

SOUL.md dan USER.md dimuat ke system prompt di setiap sesi apa pun project-nya, jadi memory global cukup mengingat personalisasi user — bukan apa yang sedang dikerjakan. MEMORY.md dimuat tambahan sesuai project aktif saja. Agent bisa update ketiganya lewat tool write file yang sama dari komponen 2; kebijakan tulis (auto-append vs konfirmasi dulu) diatur di Settings.

### 5. Sistem Skill

Bawaan (hanya yang relevan untuk dev, prioritas resolusi konflik: official > agregator > lainnya):

- `skill-creator` & `template-skill` dari anthropics/skills (Apache-2.0)
- Subset kategori Development / Infra & Git / Workflow / Testing dari sickn33/agentic-awesome-skills (MIT)
- Skill **caveman** untuk kompresi output (MIT — hanya bagian Skill, bukan Engine/Gateway yang berlisensi BSL-1.1)

Bukan sumber skill langsung: shanraisshan/claude-code-best-practice — dipakai sebagai referensi pola kerja (mis. struktur workflow) untuk menyusun `SOUL.md` default, bukan untuk di-install sebagai skill.

Install manual: skill tambahan dipasang lewat installer resmi tiap repo (`npx skills add ...`, `npx agentic-awesome-skills --skills ...`) dijalankan lewat bridge Termux:API yang sama dari komponen 2. Kalau nama skill bentrok dengan yang bawaan, app menanyakan timpa atau skip.

### 6. File Browser

File tree ringan untuk melihat struktur folder project yang sedang di-agent-kan, terintegrasi dengan riwayat sesi per-project.

### 7. Perintah Bawaan (Slash Command)

Command default yang tersedia di chat, mengikuti pola Claude Code:

- `/compact` — ringkas riwayat percakapan biar hemat token
- `/clear` — mulai sesi baru, riwayat lama disimpan terpisah
- `/memory` — buka/edit SOUL.md, USER.md, atau MEMORY.md langsung dari chat
- `/skill` — lihat, aktifkan, atau nonaktifkan skill yang terpasang
- `/model` — ganti provider atau model aktif tanpa buka Settings
- `/help` — daftar command yang tersedia

## Milestone Breakdown

- **M0 — Scaffolding**: setup project Kotlin + Compose, permission dasar, foreground service nyala-mati manual
- **M1 — UI/Provider**: chat UI ala Claude App Android, settings provider, session per project (Room), command default (/compact, /clear, /memory, /skill, /model, /help)
- **M2 — Termux bridge**: integrasi `RUN_COMMAND`, tool-calling dasar (read/write file, list dir, run shell)
- **M3 — Foreground service + Memory cap**: agent loop pindah ke service, `ulimit -v` per command, watchdog limit
- **M4 — Memory file**: auto-create `SOUL.md`/`USER.md`/`MEMORY.md`: SOUL.md & USER.md global, MEMORY.md per project, kebijakan tulis diatur di Settings
- **M5 — Sistem skill**: bundle skill default (lihat Komponen 5), loader skill per-project, jalur install manual
- **M6 — File browser**: file tree, truncate/stream output panjang
- **M7 — Polish & stabilitas**: battery/thermal check, fallback kalau Termux belum terpasang/permission ditolak

## Dependensi Eksternal

- Termux + Termux:API (harus terpasang terpisah oleh user)
- anthropics/skills — Apache-2.0 (subset: skill-creator, template-skill)
- sickn33/agentic-awesome-skills — MIT (subset kategori dev)
- caveman (JuliusBrussee) — MIT untuk bagian Skill; Engine/Gateway BSL-1.1 tidak dipakai
- shanraisshan/claude-code-best-practice — MIT, referensi pola saja

## Risiko

- Pembatasan background execution Android (Doze, battery killer OEM) bisa mengganggu foreground service meski sudah exemption
- `ulimit -v` hanya soft-limit per proses, bukan jaminan mutlak app tidak force-close dari sisi OS
- Ketergantungan pada Termux:API — kalau permission ditolak atau Termux tidak terpasang, seluruh eksekusi tool tidak jalan (perlu fallback message yang jelas di M7)
