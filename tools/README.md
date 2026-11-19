# tools/

## gh_push.py

Push commit lokal ke GitHub tanpa `git push`.

Termux git di device ini tidak punya `remote-http` helper (`git help -a` cuma
mengenali `cvsserver`/`shell`), `curl` rusak, `ssh` tidak terpasang. Jadi push
https/ssh mustahil. Script ini membangun ulang chain commit lewat Git Data API:
blob → tree → commit → ref.

```bash
GH_TOKEN=<fine-grained PAT, Contents: Read and write> python3 tools/gh_push.py
```

Catatan:
- Repo tanpa commit sama sekali kena `409 Git Repository is empty` untuk semua
  endpoint git. Script seed `PUT /contents/.seed` dulu, lalu force `main`, jadi
  commit seed jadi orphan.
- SHA di remote berbeda dari lokal: object dibuat server-side. Author/committer
  date dipertahankan supaya history tetap masuk akal.
- Karena SHA beda, `git pull`/`git status` lokal akan menganggap divergen.
  Jangan push dua arah; satu arah lokal → remote saja.
