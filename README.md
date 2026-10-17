# Novk — Kotlin VK archiver

Exports the authenticated user's accessible personal wall, including other people's posts on that wall, plus accessible photo/video/audio libraries. Posts have offline HTML, original JSON, local media, and a SHA-256 manifest. This is an API-based partial account export, not a guarantee of all VK account data.

## Build and authorize

The existing project uses JDK 26 and the Gradle wrapper. Start with [docs/AUTH.md](docs-details/AUTH.md): it explains the correct user token and includes a Mini App authorization helper.

```powershell
.\gradlew.bat check :app:installDist
# Enter the token without saving it in shell history (PowerShell 7):
$env:VK_ACCESS_TOKEN = Read-Host 'VK user access token' -MaskInput
.\app\build\install\app\bin\app.bat doctor
.\app\build\install\app\bin\app.bat archive --out D:\vk-backup
```

`doctor` identifies the user and probes permissions, wall, photos, video and audio. It does not delete anything. Default API version: 5.199; override with `VK_API_VERSION` if your app requires a different supported version.

## Archive, verify, then delete

Start with an archive-only run and open the resulting `index.html`. Every run uses a new timestamped directory; reruns preserve previous archives but download again (no cross-run resume). Each post has `manifest.json` listing files, sizes, hashes and blockers. Media is streamed to temporary files; HTTP status, declared length and image/audio/video signatures are checked before promotion. Verification proves transfer/storage consistency, not full codec decodability or the integrity of VK's original file.

```powershell
# Use the exact run path printed by archive:
.\app\build\install\app\bin\app.bat verify --out D:\vk-backup\123456-REPLACE_WITH_RUN_TIMESTAMP
# Explicit destructive mode; replace 123456 with the ID printed by doctor:
.\app\build\install\app\bin\app.bat archive --out D:\vk-backup --delete-after-verify --confirm-owner 123456
Remove-Item Env:VK_ACCESS_TOKEN
```

Destructive mode creates a fresh archive, completes wall pagination before deletion, verifies each eligible post's files, re-fetches that post, compares content and comments metadata, and only then calls `wall.delete`. An unsupported attachment, failed download, missing file, changed post or comments blocks that post's deletion. It deletes wall posts only; it does not delete library photos, videos, or audio. Library failures are reported separately and do not prevent deletion of independently verified wall posts. The account ID must match the authenticated user. No live deletion is used to test permissions.

A tiny unavoidable race remains between the final API read and deletion: VK has no conditional delete against a post revision. Avoid editing the wall while running. A deletion intent is written before the API call, followed by `acknowledged` only on response 1. An interrupted/uncertain deletion is not automatically retried; inspect `deletion.json` and VK before rerunning.

## Coverage and limits

* Wall: all posts returned by `wall.get(filter=all)`, raw JSON and escaped offline HTML; nested repost text and supported media are included.
* Images: largest returned photo size; original API metadata retained. Files have `.img` extension and display in the HTML using their file signature.
* Video: highest returned direct `mp4_*` file via `video.get`; an embedded player, external link, HLS playlist, DRM stream or thumbnail is not considered a downloaded video.
* Audio: downloads direct URLs if VK returns them. Ordinary Mini App scopes do not include audio; library access may be denied. No private API, copied official-app credentials, or DRM bypass.
* Documents: returned direct document files, saved as binary plus metadata.
* Comments/replies are **not exported**; posts reporting comments are **never deleted**. Polls, links, albums attached as albums, stories, and other unhandled attachment types are saved as metadata and block deletion. Likes/repost counts are metadata; individual liker/reposter lists are not exported.
* Messages, friends, stories, private/unavailable content, complete account settings and other account categories are not exported. Photo libraries use `photos.getAll` and include only what that method returns. For broader account data, also request VK's official account-data export through VK's privacy controls.
* A missing Content-Length or an unrecognized media signature blocks a download conservatively. Failed archives return nonzero status and report blockers; successful eligible posts may already have been deleted if destructive mode was enabled.

Archives contain personal data and possibly temporary signed media URLs/access keys. Store them on a private drive and make a second backup before enabling deletion. Tokens are read from the environment, sent only in POST bodies to the VK API, and never written by the archiver.

## Tests

` .\gradlew.bat check ` runs offline tests for deletion guards, corrupt/missing files, failed downloads, changed posts/new comments, unsupported repost attachments, pagination drift/duplicates, and HTML escaping. A real token is still necessary for live API validation.
