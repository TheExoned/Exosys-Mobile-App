# EXOSYS TACTICAL LINK (Android Host & Compiler Suite)

Companion host application, binary asset compiler, and low-latency telemetry bridge for **Exosys OS** wearable hardware (ESP32-S3 / LilyGo T-Watch Ultra).

Designed as a zero-bloat, multi-threaded Kotlin engine that offloads heavy cartographic rasterization, road-graph compilation, video delta-encoding, and passive GNSS injection from the smartwatch MCU to the Android host or a linked PC node.

---

## System Architecture & Modules

### 1. Cartographic Engine & Cluster Compiler (`.QOI` + `.HWY`)
Generates offline spatial packages tailored for direct PSRAM/MicroSD streaming on `CartoEngine`:
* **32×32 Column-Major Cluster Storage (`.QOI`):** Packs 256×256 RGB565 tiles using a custom Run-Length + 64-entry Color Hash Index codec (`QoiBinaryCodec`). Each cluster file (`C_x_y.QOI`) features an 8,192-byte little-endian pointer header (`(x % 32) * 32 + (y % 32)`) for $O(1)$ random sector seeks on the microcontroller.
* **Pixel-by-Pixel Tactical Shaders:** Real-time 2x Retina supersampling (512×512 -> 256×256) with custom luminance/road-gate isolation matrices:
  * `V1`: Dark Standard (Night Inversion + Emerald)
  * `V2`: Arasaka Grid (Night Blue / Crimson / Cyan)
  * `V3`: Kiroshi Optics (Violet / Magenta / Red)
  * `V4`: Pip-Boy 3000 CRT (Green Phosphor + High-Contrast Text Mask)
* **Anti-Wilderness Urban Mask (`Z15–Z20`):** Queries OpenStreetMap (`Overpass API`) in a two-stage streaming pipeline to rasterize populated footprints and residential street grids at `Z16`, pruning unpopulated terrain at high zoom levels while enforcing a strict Parent-Child hierarchical tile closure across `Z6–Z20`.
* **Offline Road Graph Compiler (`ARTERIAL.HWY`):** Extracts topological road networks from OSM and serializes a compact little-endian binary graph (`numNodes`, `numEdges`, `float32 lat/lon`, and directed weighted adjacency lists in meters) for offline pathfinding.

### 2. V32 Dynamic Wallpaper Studio (`.EXND` & 256×256 Map Placeholders)
Hardware-accelerated media pre-processor (`MediaCodec` YUV420 + spatial denoiser + ordered Bayer 4×4 dithering):
* **`.EXND` V32 Delta-Frame Codec (205×251 FSR):** Evaluates an 18-step compression/FPS pain ladder (`buildV31Ladder`) with fast-abort bounds checking to guarantee the output binary never exceeds the **7.4 MB** storage cap or the **120 KB** per-frame PSRAM decoder buffer (`MAX_FRAME_DECODER_BYTES`). Encodes dirty bounding boxes and skip/run delta streams against simulated VRAM.
* **Regulated Map Loading Screen (`placeholder.qoi`):** 12-stage adaptive quantizer that guarantees any static 256×256 image compresses to **$\le$ 38.0 KB** in `.QOI` (fitting inside the watch's 40 KB chunk buffer and 128 KB PSRAM slot).

### 3. Wireless Screen Caster & Bidirectional Touch Bridge
* **Low-Latency Viewport Streamer (`UDP :8888`):** Captures a `368×800` virtual display via `MediaProjection`, crops/scales into either a `368×464` segmented viewport or a `208×464` full-screen frame, compresses to RGB565/JPEG, and transmits 1,400-byte datagram chunks prefixed with the `0xEA` sync header.
* **Remote Gesture Injection (`ExosysTouchService`):** Receives normalized `TOUCH:x1,y1,x2,y2,dur` and `SCROLL_UP/DOWN` datagrams from the watch on `UDP :8889`, inverse-projects coordinates from the watch viewport to physical phone screen metrics, and dispatches hardware gestures via Android `AccessibilityService`.

### 4. Passive GNSS & BLE Beacon Telemetry (`BleCoreService`)
* **10 Hz Connectionless BLE Beacon (`0xE20A`):** Broadcasts a 12-byte little-endian manufacturer payload (`float32 lat`, `float32 lon`, `int16 alt`, `uint16 speed_tenths`) alongside a GATT Server (`E20A39F4-73F5-4BC4-A12F-17D1AD07A961`).
* **Multi-Vector Position Injection:** Simultaneously pushes `POS:lat,lon,alt,spd` frames every 1,000 ms (or 10,000 ms in Android Power Save mode) over BLE GATT notifications, BLE Manufacturer Data, and local Hotspot ARP/broadcast UDP sockets.

---

## Network Ports & Protocol Summary

| Port / UUID | Protocol | Direction | Function |
| :--- | :--- | :--- | :--- |
| **`UDP 8888`** | Datagram | Phone $\rightarrow$ Watch | `0xEA` video stream chunks & `POS:` GNSS telemetry |
| **`UDP 8889`** | Datagram | Watch $\rightarrow$ Phone | Auto-discovery (`HELLO_EXOSYS:<IP>`) & remote `TOUCH:` / `SCROLL` commands |
| **`TCP 8890`** | Socket HTTP | Phone $\leftrightarrow$ PC | `.exorecipe.json` job dispatch & remote PC/MicroSD Vault management |
| **`TCP 8892`** | Raw Socket | Phone $\leftrightarrow$ Watch | `EXUP` / `EXLS` / `EXRM` binary transfer for `.EXND` & `placeholder.qoi` |
| **`0xE20A`** | BLE Adv | Phone $\rightarrow$ Watch | 12-byte connectionless GNSS coordinate beacon |

---

## Internationalization (i18n)
Full native UI and terminal localization across **8 languages**, switchable at runtime:
* Español (`es`), English (`en`), 中文 (`zh`), 日本語 (`ja`), हिन्दी (`hi`), Français (`fr`), العربية (`ar`), Русский (`ru`).

---

## Build Requirements
* **Android Studio:** Ladybug / Koala or newer
* **Min SDK:** 26 (Android 8.0+) | **Target SDK:** 34 (Android 14)
* **Permissions Required:** `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, and `AccessibilityService` (for remote touch injection).
