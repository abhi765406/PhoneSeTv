# HomeWatch

Turns a spare Android phone into a live security camera you watch from your
main phone - live video, live two-way audio, basic motion alerts with a
siren, and snapshot evidence. Built on the same WebRTC approach as a video
call, just one phone always broadcasting instead of two people calling each
other.

This is for watching **your own property with your own phones**. It's not a
way to access anyone else's camera.

## How the two modes work

- **Camera mode** (the old Vivo): opens the back camera and mic, streams
  continuously to whichever phone connects with the matching room code. Runs
  as a foreground service so it keeps working with the screen off or the
  app not in the foreground.
- **Viewer mode** (your main phone): connects with that room code, shows the
  live feed, plays live audio automatically, and gives you two buttons:
  **Talk** (two-way audio - say something through the camera phone's
  speaker) and **Sound Siren** (remotely trigger a loud alert sound on the
  camera phone, even without motion being detected - useful the moment you
  spot something on the live feed).

## Setup

### 1. Deploy the signaling server
Same process as the other apps in this chat:
1. Create a new GitHub repo, upload the `signaling-server` folder's contents
   to its root.
2. Render → New Web Service → connect that repo → build `npm install`,
   start `npm start`.
3. (Strongly recommended) Set up free Cloudflare TURN credentials - see
   `signaling-server/README.md` for the 5-minute steps. Without this, the
   camera and viewer may fail to connect if they're on different mobile
   networks.
4. Once deployed, open `app/src/main/java/com/homewatch/app/Config.java` and
   replace both URLs with your Render service's address.

### 2. Build the app
1. Unzip `HomeWatch.zip`, upload the contents to a new GitHub repo.
2. Actions tab → let it build, or **Run workflow**.
3. Download the `HomeWatch-app` artifact, install `app-debug.apk` - **on
   both phones** (it's the same app; you just pick a different mode on
   each).

### 3. Set up the camera phone
1. Mount or prop the old phone facing what you want watched, plugged into
   power (it's a continuous camera - it will drain battery otherwise).
2. **Turn off battery optimization for HomeWatch specifically.** Vivo's
   Funtouch OS is aggressive about killing background apps - go to Settings
   → Apps → HomeWatch → Battery → set to "No restrictions" (wording varies
   by Vivo version, sometimes under "High background power consumption").
   Skipping this is the most common reason a phone-based camera randomly
   stops streaming after a while.
3. Open HomeWatch → enter a name → tap **Use This Phone As the Camera** →
   tap Generate to get a room code → note the code.
4. Tap **Arm** when you're leaving the camera unattended (this is what turns
   on motion alerts + siren + snapshots). Leave it disarmed while you're
   nearby to avoid false alerts from your own movement.

### 4. Connect from your main phone
Open HomeWatch → same name field doesn't matter → enter the room code from
the camera phone → **Watch a Camera Phone**.

## What's real, and what to expect

- **Motion detection is basic frame-difference, not AI.** It compares
  brightness across a coarse grid between frames. It will reliably catch
  someone walking into frame, a door opening, etc. It will also sometimes
  trigger on a light turning on, shadows shifting, or a curtain moving. Use
  the sensitivity slider to tune it down if it's too twitchy for your spot.
- **Snapshots save to Pictures/HomeWatch on the camera phone itself**, not
  the viewer phone (the camera phone is the one with free storage; the
  viewer is just watching). Check that folder from the camera phone's own
  Gallery/Files app after an alert.
- **The siren plays through the camera phone's speaker**, not the viewer's -
  it's meant to be heard by whoever is near the camera.
- **Data use**: capped at 480p/15fps specifically to stay light on mobile
  data if the camera phone isn't on Wi-Fi, though Wi-Fi is strongly
  preferred for a 24/7 camera.
- **This is two phones you own, talking to each other through a server you
  control.** Nothing about this accesses, scans, or connects to any device
  or camera that isn't one of your own phones running this same app.
