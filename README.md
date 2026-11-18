# Crispshot

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

| Screenshot long side | Typical phones | WhatsApp HD |
|---|---|---|
| Up to about 2460 px | Most phones at 1080p (FHD+), e.g. 1080×2400, 1080×2340, 1080×2412 | Not offered: **Crispshot helps** |
| About 2500–2799 px | 1.5K screens, e.g. 1220×2712 | Varies. Some report HD works without help |
| 2800 px or more | QHD+ phones at full resolution, e.g. 1440×3120, 1440×3200 | Offered: you don't need Crispshot |

Many QHD+ phones ship set to a lower 1080p display mode to save battery, so their screenshots fall in the
first row unless full resolution is turned on in display settings.

To check your phone, take a screenshot, send it to yourself on WhatsApp, and see whether the **HD** button
appears. You can also open the screenshot's details in your gallery app to see its size.

Crispshot leaves screenshots that are already 2800 px or more untouched.

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
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## License

MIT
