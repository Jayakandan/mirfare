# MIFARE top-up - build to APK

## What it does
Tap a MIFARE Classic 1K card:

| Mode | Effect on the card |
|---|---|
| **Read** | Shows the stored balance. Blank card reads as 0. |
| **Top-up** | Reads the balance, **ADDS** the amount you typed, writes it back, reads it back to verify. |
| **Set** | Overwrites the balance with exactly the amount you typed. |

Storage location and format match the Teensy PN532 sketch exactly, so a card
topped up on the phone can be spent on the validator:

```
BALANCE  sector 1 block 0 = absolute block 4
  bytes 0..3   'B','A','L','1'   marker
  bytes 4..7   amount, 32-bit BIG-endian
  bytes 8..15  zero

NAME     sector 1 block 1 = absolute block 5
  bytes 0..15  ASCII cardholder name, null-padded
```

Both blocks live in sector 1, so a single authentication covers both - the same
approach the Teensy sketch uses. Leave the name field blank to top up without
touching block 5.

It auto-tries the common factory keys (FFFFFFFFFFFF first). If the card uses a
custom key, the status says "Auth failed" - recover the key with the MIFARE
Classic Tool app and paste it into the Sector key field.

## Build the APK on GitHub (no local tools needed)

```bash
cd MifareAmount
git init
git add .
git commit -m "mifare top-up"
git branch -M main
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```

Then: repo -> **Actions** tab -> wait for the green run -> download
**app-debug-apk** -> unzip -> `app-debug.apk` -> install on the phone
(allow installs from unknown sources).

> On Windows, `git add .` can silently skip dot-folders. After pushing, confirm
> `.github/workflows/build.yml` is visible on GitHub. If it is missing, run
> `git add -f .github/workflows/build.yml` and push again - without it no APK is built.

## Build locally instead

The Gradle wrapper jar is not bundled, so use a system Gradle 8.7 with JDK 17:

```bash
cd MifareAmount
gradle wrapper --gradle-version 8.7   # optional: generates ./gradlew
gradle assembleDebug --no-daemon
```

The APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install it over USB with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Using top-up
1. Turn on NFC on the phone and open the app.
2. Select **Top-up**.
3. Type the amount, or tap the `+10 / +50 / +100 / +500` chips to build it up.
4. Hold the card flat on the back of the phone and keep it still.
5. The status shows `Topped up +50  (100 -> 150)` and the balance card shows the
   before and after values.

The amount box clears itself after a successful top-up, so leaving the card on
the phone cannot charge it twice.

## Notes
- The UID is factory-locked and cannot be changed; the app writes DATA only.
- Top-up accepts a negative amount to deduct; it refuses to go below zero.
- Balance is capped at 2,000,000,000 to stay inside a signed 32-bit int.
- Works only on phones with NFC hardware that reads MIFARE Classic (NXP chipset).
