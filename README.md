# Crispshot

[![CI](https://github.com/mattDev0/crispshot/actions/workflows/ci.yml/badge.svg)](https://github.com/mattDev0/crispshot/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/mattDev0/crispshot)](https://github.com/mattDev0/crispshot/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Crispshot keeps screenshots sharp when you send them on WhatsApp.

WhatsApp only offers its **HD** photo option for large images. A typical phone screenshot (1080×2400) or
laptop screenshot (1920×1080) is too small, so it goes out as a small, blurry Standard-quality JPEG with
smeared text.

Crispshot enlarges each new screenshot 2× with nearest-neighbour scaling (every pixel becomes an identical
2×2 block), so WhatsApp unlocks HD. WhatsApp then halves it back to the original size, which means the
recipient gets the screenshot at its native resolution in HD quality: no blur, and no change to how you
send.

## Do you need it?

A screenshot is the same size as your screen's current resolution. Whether WhatsApp offers HD depends on
the screenshot's long side:

| Screenshot long side | Typical phones | Full screenshot | Cropped screenshot |
|---|---|---|---|
| Up to about 2460 px | Most phones at 1080p (FHD+), e.g. 1080×2400, 1080×2340, 1080×2412 | No HD | No HD |
| About 2500–2799 px | 1.5K screens, e.g. 1220×2712 | Varies; some get HD | No HD |
| 2800 px or more | QHD+ phones at full resolution, e.g. 1440×3120, 1440×3200 | HD | No HD |

**Cropping is the catch on every phone.** Cutting a screenshot down to the part that matters makes it
too small for HD, even on a high-resolution screen. With Crispshot, full and cropped screenshots both go
out in HD. A crop is enlarged 2×, 3× or 4×, whichever is the smallest that's enough, and very small crops
are scaled up to the minimum HD size.

Many QHD+ phones ship set to a lower 1080p display mode to save battery, so their full screenshots fall in
the first row unless full resolution is turned on in display settings.

To check your phone, take a screenshot, send it to yourself on WhatsApp, and see whether the **HD** button
appears. You can also open the screenshot's details in your gallery app to see its size.

Crispshot leaves screenshots that are already large enough untouched. Crops are handled whether your
screenshot editor saves over the original or saves a new copy.

## How it works

- **Automatic.** Android's JobScheduler wakes Crispshot only when a new image is added. There's no
  background service, no ongoing notification and nothing in "Active apps" while it's idle.
- **What it touches.** New files in `Pictures/Screenshots/`. Optionally, it also handles laptop screenshots
  received through Quick Share (`Download/Quick Share/`, file names starting with "Screenshot").
  Nothing else on the phone is modified.
- **Safe.** The original file is backed up first, and the new file is verified before and after
  writing, with an automatic rollback on any failure. Originals are kept for 7 days and can be restored
  from the app.
- **Lossless.** Output PNGs are pixel-exact enlargements and keep the original's colour profile and EXIF
  data. The work runs on all background cores and usually takes under 0.2 s.
- **Cropped screenshots** use 3× or 4× when 2× isn't enough, and an exact fit to 2800 px otherwise.
- **Share target.** "WhatsApp HD" in the share sheet enlarges any shared image and hands it straight to
  WhatsApp.

## Setup

1. Install the APK from [Releases](../../releases). It requires Android 11 or newer.
2. Open Crispshot and work through the checklist:
   - **All files access**, so it can replace screenshots in place.
   - **Battery: Unrestricted**, so Android doesn't delay it when the app hasn't been opened for a while.
   - In WhatsApp, go to **Settings → Storage and data → Photo upload quality** and choose **HD**.
3. Turn **Auto-HD** on. A Quick Settings tile lets you pause it.

Then take screenshots as usual. Each one is ready within about a second, well before you can open WhatsApp
and pick it.

## Trade-offs

- Enlarged screenshots take more storage, about 1.5–2.5× the original.
- HD sends use more mobile data than Standard.
- WhatsApp HD is still a JPEG. For byte-exact files, send as a document (Attach → Document).
- WhatsApp also sends a Standard-quality copy with each HD photo, so a recipient may see it until they tap
  the HD badge.
- If you pick a screenshot within a second of taking it, you may still get the un-enlarged version.

## Building

```bash
./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease
```

Release builds are unsigned unless `CRISPSHOT_KEYSTORE_FILE`, `CRISPSHOT_KEYSTORE_PASSWORD`,
`CRISPSHOT_KEY_ALIAS` and `CRISPSHOT_KEY_PASSWORD` are set.

## CI/CD

- **CI** (`.github/workflows/ci.yml`) runs on every push and pull request: Gradle wrapper validation, Android
  Lint, unit tests and a release build. Test results go to the job summary, and reports and the APK are
  uploaded as artifacts.
- **Release** (`.github/workflows/release.yml`) runs when a `v*` tag is pushed. It checks that the tag matches
  `versionName`, builds and signs the APK with the release key from encrypted secrets, and verifies the
  signing certificate against a pinned fingerprint. It then publishes the APK and its SHA-256 checksum as a
  GitHub release, using the annotated tag message as the release notes.
- **Dependabot** opens weekly update PRs for Gradle dependencies and GitHub Actions. Actions are pinned to
  commit SHAs.

## Verifying a download

Releases from 1.0.3 onward are signed with this certificate (SHA-256):

```
55:38:F8:7D:A6:E4:C3:1F:B0:C6:A0:52:4A:26:B6:B2:1E:80:A7:C5:93:65:B0:89:6C:E2:93:04:F4:71:2B:A1
```

Check with `apksigner verify --print-certs Crispshot-<version>.apk`, and compare the APK against the
`.sha256` file attached to the release.

## License

MIT
