# Performance (v1.2.3)

Goal: the picture is sent as soon as WhatsApp allows, without changing what the app does or any safety check.

## How it was measured
There is no profiler on a phone in this project's build environment, so two things were used:
1. **Source inspection** of every step between "chat opened" and "Send tapped", listing each fixed wait, poll interval and I/O call.
2. **A simulated phone in the unit tests** (`AttachFlowTest.simulatedHappyPath_landingGallery_finishesInUnderThreeSeconds`): screens appear a fixed time after each tap
   (menu 250 ms, gallery 350 ms, send button 150 ms, new bubble 300 ms) and the app polls at its real interval. Same simulation, old vs new engine:
   **4.4 s -> 2.0 s** for the in-chat flow. Before the flow starts the old settle waits were 200 + 800 ms, now 100 + 300 ms.
   These are simulated figures for the *waiting* the app adds itself; real phones add WhatsApp's own animation time on top.
3. **Real timings from the phone**: the diagnostics (Copy diagnostics) now contain `timing:` lines - lookup duration, the time of every tap
   and of the end of the flow, measured from the moment the chat appeared, plus how long the last screen read took.

## Bottlenecks found and what was changed
| Where | Finding | Change |
|---|---|---|
| Settle before starting | 800 ms fixed wait after the chat was recognised, 200 ms event debounce | 300 ms and 100 ms (40 ms while the flow runs) |
| Polling during the flow | screen read every 400 ms (average 200 ms lost per step) | every 120 ms |
| Gap between taps | 700 ms after every tap | 350 ms (each next step is still gated by the screen showing what it needs) |
| Waiting for the gallery grid | fixed 1000 ms | as soon as the grid is *stable* (same tiles, same video marks and labels for 300 ms, at least 400 ms after opening); the 1000 ms wait remains as the fallback so video detection keeps its safety margin |
| Reading the screen | every poll also read every WhatsApp window (album dropdown popups) and built the diagnostic node report | the popup windows are skipped in the landing-gallery mode (it never opens the dropdown); the node report is built only when a new screen layout appears |
| Video lookup | the tile plan (a MediaStore query) ran on the main thread when the flow started | computed on the worker thread during the newest-photo lookup |
| Staging copy | the temporary copy of the picture was fsync'ed | no fsync (temporary file), larger copy buffer |
| License check | start-up and onResume both ran the Keystore/signature check back to back | a request that is already queued covers the next one |
| Diagnostics log | a new date formatter for every line | one shared formatter |

## Not changed (on purpose)
- Safety checks: chat title verification, newest-photo/video checks, Send only after a picture was chosen, confirmation by a new outgoing bubble.
- The phone-side wait for WhatsApp's own screens (menu and gallery animations) cannot be shortened by the app.
- Re-reading the destination before the flow starts (one extra screen read) is kept: it is the destination check.

## Risk and how to verify
Shorter waits could, on a slow phone, make the app act before WhatsApp is ready. Every action is still gated by the screen state, and the grid fast path falls back to the old 1000 ms wait.
If a send ever fails or a video is not recognised, copy the diagnostics and look at the `timing:` and `tiles:` lines.
