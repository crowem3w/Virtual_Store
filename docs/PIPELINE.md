# Live world pipeline (phase 1)

Status: phase 1 of `LIVE_WORLD_PLAN.md` is implemented in the `world/` package. The old record-then-process
scan flow (`ScanRecorder`, `Sfm`, `ReconPipeline`, `ReconService`, TSDF, `.glb` export) was removed.

## Screens
- `WorldListScreen` (home): saved worlds, New world, Open, Rename, Delete. "Store (Wi-Fi)" opens the old shop-matching screen.
- `LiveScanScreen`: black screen, the world drawn live, small camera view top-right. Start / Stop and save.
  Rotates freely. "View" toggles between a camera view and a from-behind view with a camera frustum.
- `WorldViewScreen`: orbit viewer (drag to look, pinch to zoom) for a saved world.

## Live pipeline
1. `LiveCameraPreview` binds CameraX Preview + ImageAnalysis (640x480, RGBA). `LiveAnalyzer` hands frames to `LiveSlam`.
2. `LiveSlam` (analysis thread): ORB features; two-view initialisation (essential matrix); PnP + RANSAC tracking against
   the last keyframes' map points; keyframes triangulate new sparse points. Scale is fixed so the median initial
   point is 2.5 m away. Gravity (IMU) defines "up" for the world frame (x right, y down, z forward at the start).
3. Depth worker (second thread): on each keyframe saves a JPEG + pose, runs `DepthEstimator` (MiDaS-small TFLite),
   fits it to the sparse points (1/z = a*d + b) and adds coloured points to `PointCloud` (4 cm voxel dedupe).
   Without `depth.tflite`, the sparse points are shown instead.
4. `CloudRenderer` projects the cloud on a plain Canvas (colour buckets, up to 60k points per frame).
5. Autosave (`WorldSession`): `points.bin` is appended every 8 s, plus `poses.jsonl`, `keyframes/`, `meta.json`;
   `thumbnail.jpg` is written on Stop.

## Saved world layout
```
filesDir/worlds/<id>/
  meta.json     name, times, point and keyframe counts, align_slam_to_world (3x3, row-major)
  points.bin    16-byte records: x, y, z (float32 LE), ARGB (int32 LE), gravity-aligned world frame
  poses.jsonl   one line per keyframe: id, rot_deg, R (world->camera, SLAM frame), t
  keyframes/    00000.jpg ...
  thumbnail.jpg
```

## Needs
`app/src/main/assets/models/depth.tflite` (MiDaS-small, float32, 256x256 NHWC). Input size/layout are read from the model;
ImageNet mean/std normalisation is in `DepthEstimator.MEAN/STD`.

## Not done yet (phases 2 and 3)
Incremental live mesh, device performance tiers, loop closure / drift correction, texture baking, `.glb` export,
resuming and extending a saved world (a saved world can only be viewed for now).
