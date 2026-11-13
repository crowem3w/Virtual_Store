# On-device scan pipeline

Status: stages 1-5 are implemented (`recon/` package). Stage 6 is a button + foreground service, not WorkManager.

1. **Capture:** `ScanScreen` / `ScanRecorder` save sharp keyframes to `filesDir/scans/<id>/` with
   `frames.jsonl` (phone orientation + rotation per frame), `meta.json` (intrinsics guess, device, Wi-Fi fingerprint).
2. **Camera poses (`Sfm`):** ORB features, essential-matrix initialisation, PnP registration of each frame,
   midpoint triangulation. OpenCV (Maven AAR). No bundle adjustment yet: long loops will drift.
3. **Alignment (`Alignment`):** gravity from the IMU (image-based fallback) -> +Y up; floor detected as the lowest
   well-populated horizontal slab; scale so the camera was 1.4 m above the floor (`Alignment.CAMERA_HEIGHT_M`);
   first camera at x=z=0 looking toward -Z. The store's "Adjust model" buttons fix residual scale error.
4. **Depth (`DepthEstimator`):** TFLite monocular depth, fitted per frame to the sparse SfM points (1/z = a*d + b).
   **Needs `app/src/main/assets/models/depth.tflite`** (float32 image in, relative inverse depth out; MiDaS-small or
   Depth-Anything-small style). Input size/layout are read from the model; ImageNet mean/std normalisation is in
   `DepthEstimator.MEAN/STD`.
5. **Fusion + mesh (`VoxelGrid`, `SurfaceNets`):** sparse TSDF (4 cm voxels, coarser for big shops), naive surface nets,
   small floating fragments removed, vertex colours from the keyframes.
6. **Export (`GlbWriter`):** `filesDir/scans/<id>/model.glb` (POSITION, NORMAL, COLOR_0, unlit material).
   `StoreScreen` loads it via `ShopRegistry.scanShop(info)` ("Open in store" on the scan row).
7. **Run (`ReconService`):** "Process to 3D" starts a foreground service (progress notification, wake lock, one job at
   a time, cancellable). `recon_log.txt` in the scan folder records what each stage did.

Not done yet: bundle adjustment / loop closure, texture atlas (vertex colours are used instead), mesh decimation,
scheduled overnight processing.
