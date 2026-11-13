# On-device scan pipeline (plan)

1. **Capture (done):** `ScanScreen` / `ScanRecorder` save sharp keyframes to `filesDir/scans/<id>/` with
   `frames.jsonl` (phone orientation per frame), `meta.json` (intrinsics guess, device, Wi-Fi fingerprint).
2. **Camera poses:** features + matching + incremental structure-from-motion (OpenCV, NDK).
3. **Depth:** monocular depth network (TFLite), scaled with the SfM sparse points.
4. **Fusion:** TSDF volume -> marching cubes -> mesh, vertex colours.
5. **Texturing:** UV atlas from the best keyframe per triangle -> glTF (.glb) -> `StoreScreen`.
6. **Overnight job:** foreground service / WorkManager, charging only.

Prototype stages 2-5 in Python/OpenCV on real captures first, then port to Kotlin.
